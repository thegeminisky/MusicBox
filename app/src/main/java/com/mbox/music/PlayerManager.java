package com.mbox.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局播放器。挂在应用级，歌单页与播放器页共享同一份队列与播放状态，
 * 页面之间来回切换不会打断播放。
 */
public class PlayerManager {

    private static final String TAG = "MBoxPlayer";

    public static final int MODE_SEQUENCE = 0;    // 顺序播放，放完即止
    public static final int MODE_LOOP_LIST = 1;   // 列表循环
    public static final int MODE_LOOP_ONE = 2;    // 单曲循环

    private static final long URL_TTL = 15 * 60 * 1000L;

    public interface Listener {
        /** 曲目、播放状态、模式等发生变化 */
        void onPlayerChanged();

        /** 播放进度（约 500ms 一次），单独回调避免整页重绘 */
        default void onProgress(int pos, int dur) {
        }
    }

    private static PlayerManager instance;

    public static synchronized PlayerManager get(Context c) {
        if (instance == null) instance = new PlayerManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<NeteaseApi.Song> queue = new ArrayList<>();

    private int index = -1;
    private int mode = MODE_LOOP_LIST;
    private MediaPlayer mp;
    private boolean preparing;
    private boolean playing;
    private String lastError;

    /** 每次 startMp 递增，用于丢弃过期的回调（快速切歌时尤其重要）。 */
    private int gen;
    private Runnable prepareTimeout;
    private Runnable notifyRunnable;
    /** 连续失败计数，播放满 5 秒才清零。 */
    private int fastFailCount;
    /** 当前曲目已尝试过的镜像序号。 */
    private int mirrorIdx;
    /** 当前曲目「重新取地址」用过几次，避免失败时无限重试。 */
    private int refetchTries;
    private android.media.AudioFocusRequest focusRequest;
    private boolean pausedByFocus;
    /** 最近一次成功 prepare 的 gen，用来识别 MIUI 的误报 onError。 */
    private int lastPreparedGen = -1;

    private PlayerManager(Context c) {
        app = c;
        watchHeadsetUnplug();
    }

    /**
     * 拔出耳机/断开蓝牙时暂停。开关在设置页，默认开。
     * 用 AudioDeviceCallback 而不是广播监听有线耳机状态——后者对蓝牙无效。
     */
    private void watchHeadsetUnplug() {
        final android.media.AudioManager am =
                (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.registerAudioDeviceCallback(new android.media.AudioDeviceCallback() {
                @Override
                public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
                    if (!sp().getBoolean(MainActivity.KEY_PAUSE_ON_UNPLUG, true)) return;
                    for (android.media.AudioDeviceInfo d : removed) {
                        int t = d.getType();
                        boolean headset = t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
                        if (!headset) continue;
                        if (playing) ui.post(() -> {
                            if (playing) toggle();
                        });
                        break;
                    }
                }
            }, null);
        } catch (Throwable e) {
            Log.w(TAG, "watchHeadsetUnplug failed " + e);
        }
    }

    // ------------------------------------------------------------ 设置读取

    private SharedPreferences sp() {
        return app.getSharedPreferences(MainActivity.SP, Context.MODE_PRIVATE);
    }

    private String token() {
        return sp().getString(MainActivity.KEY_MUSIC_U, null);
    }

    public String level() {
        String lv = sp().getString(MainActivity.KEY_LEVEL, "exhigh");
        return NeteaseApi.isValidLevel(lv) ? lv : "exhigh";
    }

    // ------------------------------------------------------------ 观察者

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 合并短时间内的多次状态变更，避免主线程被 notifyDataSetChanged 淹没。 */
    private void notifyChanged() {
        if (notifyRunnable != null) ui.removeCallbacks(notifyRunnable);
        notifyRunnable = new Runnable() {
            @Override
            public void run() {
                notifyRunnable = null;
                for (Listener l : listeners) l.onPlayerChanged();
            }
        };
        ui.post(notifyRunnable);
    }

    // ------------------------------------------------------------ 查询

    public List<NeteaseApi.Song> queue() {
        return queue;
    }

    public int index() {
        return index;
    }

    public NeteaseApi.Song current() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPreparing() {
        return preparing;
    }

    public int mode() {
        return mode;
    }

    public String lastError() {
        return lastError;
    }

    public int position() {
        try {
            return mp != null && playing ? mp.getCurrentPosition() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public int duration() {
        try {
            return mp != null ? mp.getDuration() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 控制

    /**
     * 换播放队列。
     *
     * @param newMode 为 null 时保持当前模式；「播放全部」传 MODE_LOOP_LIST。
     */
    public void setQueue(List<NeteaseApi.Song> songs, int start, boolean autoPlay, Integer newMode) {
        queue.clear();
        if (songs != null) queue.addAll(songs);
        if (newMode != null) mode = newMode;
        index = -1;
        fastFailCount = 0;
        if (autoPlay && !queue.isEmpty()) {
            playAt(Math.max(0, Math.min(start, queue.size() - 1)));
        } else {
            notifyChanged();
        }
    }

    public void playAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        mirrorIdx = 0;
        refetchTries = 0;
        final NeteaseApi.Song s = queue.get(i);
        Log.d(TAG, "playAt #" + i + "/" + queue.size() + " id=" + s.id
                + " name=" + s.name + " hasUrl=" + (s.url != null)
                + " hasPic=" + (s.picUrl != null && !s.picUrl.isEmpty()));
        if (s.picUrl != null && !s.picUrl.isEmpty()) {
            CoverLoader.prefetch(app, s.picUrl, 640);
            CoverLoader.prefetch(app, s.picUrl, 160);
        }
        preparing = true;
        playing = false;
        lastError = null;
        notifyChanged();

        pool.execute(() -> {
            final String url = ensurePlayable(s);
            ui.post(() -> {
                if (index != i) return;
                if (url == null) {
                    failCurrent("当前网络下不可播（版权限制）");
                } else {
                    startMp(url);
                    // 当前曲听完这次就进本地缓存，下次直接秒开
                    AudioCache.get(app).download(s);
                    // 拉起前台服务：撑住后台播放 + 通知栏控件
                    PlayerService.start(app);
                }
            });
        });
        prefetch(i + 1);
    }

    private String ensurePlayable(NeteaseApi.Song s) {
        synchronized (s) {
            // 本地已缓存就直接播文件，离线也能听
            AudioCache ac = AudioCache.get(app);
            java.io.File local = ac.find(s.id);
            if (local != null) {
                ac.touch(s.id);
                Log.d(TAG, "ensure LOCAL id=" + s.id + " " + local.getName()
                        + " " + (local.length() / 1024) + "KB");
                return local.getAbsolutePath();
            }
            long age = System.currentTimeMillis() - s.urlTs;
            if (s.url != null && age < URL_TTL) {
                // 节点状态会在几十秒内变化（m802 实测 30s 前能播、之后返回 HTML）。
                // 刚解析出来的 (<5s) 直接用，否则复用前必须重新验一次内容，
                // 否则 MediaPlayer 会拿到非音频数据报 -38。
                if (age < 5000 || NeteaseApi.probeOk(s.url)) {
                    Log.d(TAG, "ensure HIT id=" + s.id + " age=" + (age / 1000) + "s host="
                            + NeteaseApi.hostOf(s.url));
                    return s.url;
                }
                Log.w(TAG, "ensure STALE id=" + s.id + " age=" + (age / 1000)
                        + "s host=" + NeteaseApi.hostOf(s.url) + " -> refetch");
                s.url = null;
                s.urlTs = 0;
                s.rawUrl = null;
            }
            String t = token();
            if (t == null) {
                Log.w(TAG, "ensure NO_TOKEN");
                return null;
            }
            try {
                long t0 = System.currentTimeMillis();
                new NeteaseApi(t).fillUrls(Collections.singletonList(s), level());
                Log.d(TAG, "ensure FETCH id=" + s.id + " lv=" + level()
                        + " cost=" + (System.currentTimeMillis() - t0) + "ms got=" + (s.url != null));
            } catch (Exception e) {
                Log.w(TAG, "ensure FETCH_FAIL id=" + s.id + " " + e);
                return null;
            }
            if (s.url == null) {
                Log.w(TAG, "ensure NO_URL id=" + s.id + " name=" + s.name);
                return null;
            }
            long t1 = System.currentTimeMillis();
            String base = s.rawUrl != null ? s.rawUrl : s.url;
            String resolved = NeteaseApi.resolvePlayable(base);
            Log.d(TAG, "ensure RESOLVE id=" + s.id + " cost=" + (System.currentTimeMillis() - t1) + "ms"
                    + " -> " + (resolved == null ? "NULL(不可播)" : NeteaseApi.hostOf(resolved)));
            s.url = resolved;
            s.urlTs = System.currentTimeMillis();
            return s.url;
        }
    }

    private void prefetch(int i) {
        // 连续失败跳歌时不要再预取，否则每跳一首就发一串请求，形成瀑布
        if (fastFailCount >= 2) return;
        if (i < 0 || i >= queue.size()) return;
        final NeteaseApi.Song s = queue.get(i);
        // 封面一起预取，切歌时播放器页能瞬间出图，不会白一下
        if (s.picUrl != null && !s.picUrl.isEmpty()) {
            CoverLoader.prefetch(app, s.picUrl, 640);
            CoverLoader.prefetch(app, s.picUrl, 160);
        }
        if (AudioCache.get(app).has(s.id)) return;
        final boolean preload = sp().getBoolean(MainActivity.KEY_PRELOAD_NEXT, true);
        pool.execute(() -> {
            if (ensurePlayable(s) != null && preload) {
                AudioCache.get(app).download(s);
            }
        });
    }

    /**
     * prepareAsync 在个别情况下既不回调 onPrepared 也不回调 onError（快速 release/reset
     * 之后最容易出现），会永久卡在「缓冲中」。这里用 generation + 超时兜底。
     */
    private static final int PREPARE_TIMEOUT_MS = 8000;

    /** 申请音频焦点：来电或其它 App 播放时会收到回调，避免混音。 */
    private boolean requestFocus() {
        android.media.AudioManager am =
                (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return true;
        if (focusRequest == null) {
            focusRequest = new android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(this::onFocusChange)
                    .build();
        }
        return am.requestAudioFocus(focusRequest)
                == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        android.media.AudioManager am =
                (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
    }

    private void onFocusChange(int change) {
        ui.post(() -> {
            switch (change) {
                case android.media.AudioManager.AUDIOFOCUS_LOSS:
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (playing) {
                        pausedByFocus = true;
                        toggle();
                    }
                    break;
                case android.media.AudioManager.AUDIOFOCUS_GAIN:
                    // 只有「因为失去焦点才暂停」的情况才自动恢复，用户自己暂停的不动
                    if (pausedByFocus && !playing) {
                        pausedByFocus = false;
                        toggle();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void startMp(String url) {
        final int myGen = ++gen;
        if (!requestFocus()) Log.w(TAG, "audio focus denied, play anyway");
        releaseMp();
        preparing = true;
        Log.d(TAG, "startMp gen=" + myGen + " host=" + NeteaseApi.hostOf(url)
                + " len=" + (url == null ? 0 : url.length()));
        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(url);
            p.setOnPreparedListener(m -> {
                if (myGen != gen) {
                    Log.w(TAG, "onPrepared STALE gen=" + myGen + " cur=" + gen);
                    return;
                }
                cancelPrepareTimeout();
                preparing = false;
                playing = true;
                lastPreparedGen = myGen;
                m.start();
                notifyChanged();
                tick();
            });
            p.setOnCompletionListener(m -> {
                if (myGen == gen) onComplete();
            });
            p.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "onError gen=" + myGen + " what=" + what + " extra=" + extra
                        + " host=" + NeteaseApi.hostOf(url));
                if (myGen != gen) return true;
                cancelPrepareTimeout();
                String why;
                if (what == -38) why = "无法播放（格式不支持）";
                else if (what == 1) why = "无法播放（数据异常）";
                else why = "无法播放（" + what + "）";
                // MIUI 会在 prepareAsync 时先误报一次 -38，几十毫秒后照样 onPrepared。
                // 立即 retryOrFail 会把刚播上的实例覆盖掉，表现就是胶囊疯狂跳歌。
                // 所以延迟判定：期间若已 prepare 成功，就当无事发生。
                final String finalWhy = why;
                ui.postDelayed(() -> {
                    if (myGen != gen || lastPreparedGen == myGen) {
                        return;
                    }
                    retryOrFail(finalWhy);
                }, 600);
                return true;
            });
            p.prepareAsync();
            schedulePrepareTimeout(myGen);
        } catch (Exception e) {
            Log.w(TAG, "startMp EXCEPTION gen=" + myGen + " " + e);
            failCurrent("播放失败：" + NeteaseApi.friendlyError(e));
        }
    }

    private void schedulePrepareTimeout(final int myGen) {
        cancelPrepareTimeout();
        prepareTimeout = () -> {
            prepareTimeout = null;
            if (myGen != gen) return;
            Log.w(TAG, "prepare TIMEOUT gen=" + myGen + " (" + (PREPARE_TIMEOUT_MS / 1000) + "s)");
            releaseMp();
            retryOrFail("准备超时");
        };
        ui.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
    }

    private void cancelPrepareTimeout() {
        if (prepareTimeout != null) {
            ui.removeCallbacks(prepareTimeout);
            prepareTimeout = null;
        }
    }

    /**
     * 同一首歌先换 CDN 节点重试（最多 3 个），全失败才判定不可播、跳下一首。
     * 「16 字节探测通过」不代表 MediaPlayer 能持续拉流 —— m704/m804 就是探测通过但
     * prepare 卡到超时，所以必须有这层节点级重试。
     */
    private void retryOrFail(String why) {
        final NeteaseApi.Song s = current();
        if (s != null) {
            // 第一次失败：整个地址可能已失效，重新向接口取一次（新签名 + 新节点）。
            // 注意不能调 playAt，那会把重试计数清零导致死循环。
            if (refetchTries < 1) {
                refetchTries++;
                // 如果刚才是本地缓存文件播失败（-38），说明缓存本身是坏的，
                // 必须删掉，否则重试又会命中同一个坏文件无限循环
                java.io.File bad = AudioCache.get(app).find(s.id);
                if (bad != null) {
                    Log.w(TAG, "drop corrupt cache id=" + s.id + " " + (bad.length() / 1024) + "KB");
                    bad.delete();
                }
                synchronized (s) {
                    s.url = null;
                    s.urlTs = 0;
                    s.rawUrl = null;
                }
                Log.w(TAG, "retry refetch id=" + s.id + " (" + why + ")");
                final int myIdx = index;
                pool.execute(() -> {
                    final String u = ensurePlayable(s);
                    ui.post(() -> {
                        if (index != myIdx) return;
                        if (u == null) {
                            failCurrent(why);
                        } else {
                            startMp(u);
                        }
                    });
                });
                return;
            }
            // 之后在原始/当前地址上换同族镜像
            String base = s.rawUrl != null ? s.rawUrl : s.url;
            if (base != null) {
                while (mirrorIdx < 3) {
                    String alt = NeteaseApi.mirrorAt(base, mirrorIdx);
                    mirrorIdx++;
                    if (alt == null) break;
                    if (alt.equals(s.url)) continue;
                    Log.w(TAG, "retry mirror #" + mirrorIdx + " -> " + NeteaseApi.hostOf(alt)
                            + " (" + why + ")");
                    s.url = alt;
                    startMp(alt);
                    return;
                }
            }
        }
        failCurrent(why);
    }

    /**
     * 统一失败处理：累加失败计数，达到阈值就停下。
     * 注意不能用时间窗口判断「连续」——断网时单次失败本身就要等十几秒超时，
     * 时间窗口永远不成立，结果就是在整张列表上无限往后跳。
     * 只有真正播放满 5 秒才会把计数清零（见 tick）。
     */
    private void failCurrent(String why) {
        preparing = false;
        playing = false;
        fastFailCount++;
        Log.w(TAG, "failCurrent idx=" + index + " count=" + fastFailCount + " why=" + why);

        if (fastFailCount >= 4) {
            fastFailCount = 0;
            lastError = "连续多首无法播放，已暂停";
            notifyChanged();
            return;
        }
        lastError = why;
        notifyChanged();
        advance(false);
    }

    private void onComplete() {
        playing = false;
        if (mode == MODE_LOOP_ONE) {
            playAt(index);
            return;
        }
        advance(true);
    }

    /**
     * @param allowWrap 正常播完可以回绕到列表开头；失败跳歌不允许——
     *                  否则点最后一首播不了会莫名其妙跳回第一首。
     */
    private void advance(boolean allowWrap) {
        if (queue.isEmpty()) return;
        int n = index + 1;
        if (n >= queue.size()) {
            if (mode == MODE_LOOP_LIST && allowWrap) {
                n = 0;
            } else {
                playing = false;
                if (!allowWrap) lastError = "已到列表末尾，后面的曲子都无法播放";
                notifyChanged();
                return;
            }
        }
        playAt(n);
    }

    public void toggle() {
        if (current() == null) return;
        if (mp == null) {
            playAt(Math.max(0, index));
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                playing = false;
            } else {
                mp.start();
                playing = true;
                tick();
            }
            notifyChanged();
        } catch (Throwable ignored) {
        }
    }

    public void next() {
        if (queue.isEmpty()) return;
        int n = index + 1;
        if (n >= queue.size()) {
            if (mode == MODE_LOOP_LIST) n = 0;
            else {
                n = queue.size() - 1;
            }
        }
        playAt(n);
    }

    public void prev() {
        if (queue.isEmpty()) return;
        int n = index - 1;
        if (n < 0) n = (mode == MODE_LOOP_LIST) ? queue.size() - 1 : 0;
        playAt(n);
    }

    public void seekTo(int ms) {
        try {
            if (mp != null) {
                mp.seekTo(ms);
                notifyChanged();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 从播放列表移除一首；删的正好是当前曲目时会自动接着播下一首。 */
    public void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        boolean isCur = (i == index);
        queue.remove(i);
        if (queue.isEmpty()) {
            index = -1;
            stop();
            return;
        }
        if (isCur) {
            index = Math.min(i, queue.size() - 1);
            playAt(index);
        } else {
            if (i < index) index--;
            notifyChanged();
        }
    }

    /** 把第 from 首移到第 to 位，当前播放索引跟着调整。 */

    /** 顺序 → 列表循环 → 单曲循环 → 顺序 */
    public void cycleMode() {
        mode = (mode + 1) % 3;
        notifyChanged();
    }

    public static String modeLabel(int m) {
        switch (m) {
            case MODE_LOOP_ONE: return "单曲循环";
            case MODE_SEQUENCE: return "顺序播放";
            default: return "列表循环";
        }
    }

    /** 模式对应的矢量图标资源，避免用 emoji 导致风格割裂。 */
    public static int modeIconRes(int m) {
        switch (m) {
            case MODE_LOOP_ONE:
                return R.drawable.ic_mode_one;
            case MODE_SEQUENCE:
                return R.drawable.ic_mode_sequence;
            default:
                return R.drawable.ic_mode_loop;
        }
    }

    private void tick() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!playing || mp == null) return;
                int p = position();
                int d = duration();
                // 真的播过 5 秒才算这首成功，才允许清掉连续失败计数
                if (p > 5000) fastFailCount = 0;
                for (Listener l : listeners) l.onProgress(p, d);
                ui.postDelayed(this, 500);
            }
        }, 500);
    }

    private void releaseMp() {
        cancelPrepareTimeout();
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
    }

    public void stop() {
        abandonFocus();
        pausedByFocus = false;
        releaseMp();
        playing = false;
        preparing = false;
        notifyChanged();
        PlayerService.stop(app);
    }
}
