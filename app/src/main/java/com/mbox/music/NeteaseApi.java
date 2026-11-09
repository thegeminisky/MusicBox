package com.mbox.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐接口封装。只用 HttpURLConnection + org.json，零第三方依赖。
 *
 * 唯一凭据是 cookie MUSIC_U —— 由 LoginActivity 的 WebView 登录后从 CookieManager 取得。
 */
public final class NeteaseApi {

    static final String BASE = "https://music.163.com";

    /** 由 DeviceProfile 生成的客户端标识 cookie（os/appver/brand/NMCID/deviceId…）。 */
    private static String sExtraCookie = "";

    public static void setExtraCookie(String extra) {
        sExtraCookie = extra == null ? "" : extra;
    }

    private String cookie() {
        StringBuilder sb = new StringBuilder();
        sb.append("MUSIC_U=").append(musicU);
        sb.append("; os=android");
        sb.append("; appver=9.6.22");
        sb.append("; channel=xiaomi");
        sb.append("; versioncode=9006022");
        if (!sExtraCookie.isEmpty()) sb.append("; ").append(sExtraCookie);
        return sb.toString();
    }
    static final String TAG = "MBoxApi";
    static final String UA = "NeteaseMusic/9.6.22.261106000518(9006022);"
            + "Dalvik/2.1.0 (Linux; U; Android 13; M2012K11AC Build/TKQ1.221114.001)";

    private final String musicU;

    public NeteaseApi(String musicU) {
        this.musicU = musicU;
    }

    public static final class Song {
        public long id;
        public String name = "";
        public String artist = "";
        public String album = "";
        public long duration;
        public int fee;
        public String url;
        public String type = "";
        public String picUrl = "";
        /** 接口返回的原始地址，用于后续换节点重试。 */
        public String rawUrl;
        /** 该 URL 的获取时刻，用于判断是否已接近 25 分钟的有效期。 */
        public long urlTs;
    }

    public static final class Profile {
        public long uid;
        public String nickname = "";
        public String avatarUrl = "";
        public int vipType;
    }

    // ---------------------------------------------------------------- HTTP

    private JSONObject request(String path, Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        }
        byte[] body = sb.toString().getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cookie", cookie());
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://music.163.com/");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setFixedLengthStreamingMode(body.length);

        OutputStream os = c.getOutputStream();
        os.write(body);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            text = bo.toString("UTF-8");
        }
        c.disconnect();
        if (text.isEmpty()) throw new IllegalStateException("空响应 HTTP " + code);
        return new JSONObject(text);
    }

    private static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------- 账号

    public Profile account() throws Exception {
        JSONObject d = request("/api/nuser/account/get", form());
        Profile p = new Profile();
        JSONObject acc = d.optJSONObject("account");
        if (acc == null) return null;                    // 未登录 / MUSIC_U 失效
        p.uid = acc.optLong("id");
        p.vipType = acc.optInt("vipType");
        JSONObject prof = d.optJSONObject("profile");
        if (prof != null) {
            p.nickname = prof.optString("nickname", "");
            p.avatarUrl = prof.optString("avatarUrl", "");
        }
        if (p.nickname.isEmpty()) p.nickname = String.valueOf(p.uid);
        return p;
    }

    // --------------------------------------------------------- 每日推荐

    public List<Song> dailySongs() throws Exception {
        JSONObject d = request("/api/v1/discovery/recommend/songs", form());
        return parseSongs(d.optJSONArray("recommend"));
    }

    // --------------------------------------------------------- 红心歌单

    /** 「我喜欢的音乐」其实是一个 specialType=5 的歌单，先枚举再回退到 uid 同名歌单。 */
    public long likePlaylistId(long uid) throws Exception {
        try {
            JSONObject d = request("/api/user/playlist",
                    form("uid", String.valueOf(uid), "limit", "1000", "offset", "0"));
            JSONArray arr = d.optJSONArray("playlist");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null && p.optInt("specialType") == 5) return p.optLong("id");
                }
            }
        } catch (Exception ignored) {
            // 枚举失败就直接走 uid 回退
        }
        return uid;
    }

    public List<Song> playlistSongs(long playlistId) throws Exception {
        JSONObject d = request("/api/v6/playlist/detail",
                form("id", String.valueOf(playlistId), "n", "1000", "s", "0"));
        JSONObject pl = d.optJSONObject("playlist");
        if (pl == null) return new ArrayList<>();
        return parseSongs(pl.optJSONArray("tracks"));
    }

    // --------------------------------------------------------- 播放地址

    /** 音质从高到低。设置里选了某一档，就从这个位置往下退到能取到为止。 */
    static final String[] LEVEL_CHAIN = {"hires", "lossless", "exhigh", "higher", "standard"};

    public static String[] levels() {
        return LEVEL_CHAIN.clone();
    }

    public static boolean isValidLevel(String lv) {
        for (String x : LEVEL_CHAIN) if (x.equals(lv)) return true;
        return false;
    }

    public static String levelLabel(String lv) {
        switch (lv) {
            case "hires": return "Hi-Res";
            case "lossless": return "无损";
            case "exhigh": return "极高";
            case "higher": return "较高";
            default: return "标准";
        }
    }

    /**
     * 批量换取临时播放地址（CDN 地址约 25 分钟过期）。
     * 从 level 开始逐级降档，直到拿到 url —— 非会员或无该音质的歌会自动退到能播的档位。
     */
    public void fillUrls(List<Song> songs, String level) throws Exception {
        if (songs == null || songs.isEmpty()) return;
        int start = 0;
        for (int i = 0; i < LEVEL_CHAIN.length; i++) {
            if (LEVEL_CHAIN[i].equals(level)) {
                start = i;
                break;
            }
        }
        Map<Long, JSONObject> got = new HashMap<>();
        for (int i = start; i < LEVEL_CHAIN.length; i++) {
            List<Song> missing = new ArrayList<>();
            for (Song s : songs) {
                JSONObject cur = got.get(s.id);
                if (cur == null || urlOf(cur) == null) missing.add(s);
            }
            if (missing.isEmpty()) break;
            Map<Long, JSONObject> batch = fetchUrlBatch(missing, LEVEL_CHAIN[i]);
            for (Map.Entry<Long, JSONObject> e : batch.entrySet()) {
                String u = urlOf(e.getValue());
                if (u != null) {
                    got.put(e.getKey(), e.getValue());
                    Log.d(TAG, "url ok  lv=" + LEVEL_CHAIN[i] + " id=" + e.getKey()
                            + " code=" + e.getValue().optInt("code")
                            + " br=" + e.getValue().optLong("br")
                            + " type=" + e.getValue().optString("type", "")
                            + " host=" + hostOf(u)
                            // 带 cdntag 说明是网页端身份签发的地址，容易被节点拒
                            + (u.contains("cdntag=") ? "  [WEB-MARK]" : ""));
                } else {
                    Log.d(TAG, "url none lv=" + LEVEL_CHAIN[i] + " id=" + e.getKey()
                            + " code=" + e.getValue().optInt("code"));
                }
            }
        }
        for (Song s : songs) {
            JSONObject o = got.get(s.id);
            if (o == null) continue;
            s.url = urlOf(o);
            s.rawUrl = s.url;
            s.type = o.optString("type", "");
            if (s.url != null) s.urlTs = System.currentTimeMillis();
        }
    }

    private Map<Long, JSONObject> fetchUrlBatch(List<Song> songs, String level) throws Exception {
        Map<Long, JSONObject> map = new HashMap<>();
        for (int off = 0; off < songs.size(); off += 100) {
            JSONArray ids = new JSONArray();
            for (int i = off; i < Math.min(off + 100, songs.size()); i++) ids.put(songs.get(i).id);
            // encodeType=flac 在各档位都比 aac 给得更高：无损 1681k vs 912k，
            // 极高 320k mp3 vs 256k m4a，标准 128k vs 96k
            JSONObject d = request("/api/song/enhance/player/url/v1",
                    form("ids", ids.toString(), "level", level, "encodeType", "flac"));
            JSONArray arr = d.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) map.put(o.optLong("id"), o);
                }
            }
        }
        return map;
    }

    // --------------------------------------------------------- 心动模式

    /**
     * 心动模式：以某首歌为种子，沿歌单风格连续推荐。
     * 该接口只返回 songId，需要再查 song/detail 补全标题、歌手与封面。
     */
    public List<Song> heartbeatSongs(long playlistId, long startSongId, int count) throws Exception {
        JSONObject d = request("/api/playmode/intelligence/list",
                form("songId", String.valueOf(startSongId),
                        "type", "fromPlayOne",
                        "playlistId", String.valueOf(playlistId),
                        "startMusicId", String.valueOf(startSongId),
                        "count", String.valueOf(count)));
        List<Long> ids = new ArrayList<>();
        JSONArray arr = d.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    long id = o.optLong("id");
                    if (id > 0) ids.add(id);
                }
            }
        }
        return songDetail(ids);
    }

    /** 批量补全歌曲信息（标题 / 歌手 / 封面 / 时长）。 */
    public List<Song> songDetail(List<Long> ids) throws Exception {
        List<Song> out = new ArrayList<>();
        for (int off = 0; off < ids.size(); off += 100) {
            JSONArray arr = new JSONArray();
            for (int i = off; i < Math.min(off + 100, ids.size()); i++) arr.put(ids.get(i));
            JSONObject d = request("/api/song/detail", form("ids", arr.toString()));
            out.addAll(parseSongs(d.optJSONArray("songs")));
        }
        return out;
    }

    private static String urlOf(JSONObject o) {
        Object v = o.opt("url");
        if (v == null || v == JSONObject.NULL) return null;
        String s = String.valueOf(v);
        return (s.isEmpty() || "null".equals(s)) ? null : s;
    }

    // ------------------------------------------------- CDN 节点可用性

    /**
     * 网易云签发的 CDN 域名不一定可用：实测同一 path 下 m704/m804 返回 403，
     * 换 m701/m801 就恢复 206。而 /ymusic/ 这类老路径反过来只能用 m7/m8/m10 系列。
     * 所以播放前先探一次，403 就按存储路径换同族节点重试。
     */
    private static final String[] MIRRORS_JDY = {"m701", "m801", "m702", "m802", "m800"};
    private static final String[] MIRRORS_YMUSIC = {"m7", "m8", "m10", "m1", "m2", "m9"};

    /**
     * @return 真正可下载的地址；所有节点都不可用时返回 null。
     *
     * 说明：早先这里硬编码排除了 m704/m804，那是治标。真正的原因是取地址时
     * 没带 `os` 客户端标识，服务端按网页端签发了带 `cdntag=mark=os_web` 的地址，
     * 分配到防盗链更严的节点。带上 `os=pc` 后分配的就是客户端通道地址，不再需要黑名单。
     */
    public static String resolvePlayable(String url) {
        if (url == null) return null;
        long t0 = System.currentTimeMillis();
        if (probeOk(url)) {
            return url;
        }
        for (String host : mirrorsFor(url)) {
            String alt = withHost(url, host);
            if (alt == null) continue;
            long t = System.currentTimeMillis();
            if (probeOk(alt)) {
                return alt;
            }
        }
        Log.w(TAG, "probe ALL FAILED " + hostOf(url) + " path=" + pathHint(url));
        return null;
    }

    /** 取第 idx 个候选镜像，供播放失败后换节点重试。 */
    public static String mirrorAt(String rawUrl, int idx) {
        if (rawUrl == null) return null;
        String cur = hostPrefix(rawUrl);
        int n = 0;
        for (String host : mirrorsFor(rawUrl)) {
            if (host.equals(cur)) continue;
            if (n == idx) return withHost(rawUrl, host);
            n++;
        }
        return null;
    }

    static String hostPrefix(String url) {
        try {
            String h = new URL(url).getHost();
            if (h == null) return "";
            int dot = h.indexOf('.');
            return dot > 0 ? h.substring(0, dot) : h;
        } catch (Exception e) {
            return "";
        }
    }

    static String hostOf(String url) {
        if (url == null) return "null";
        if (!url.startsWith("http")) return "local";   // 本地缓存文件路径
        try {
            String h = new URL(url).getHost();
            return h == null ? "?" : h;
        } catch (Exception e) {
            return "bad-url";
        }
    }

    private static String pathHint(String url) {
        try {
            String p = new URL(url).getPath();
            if (p == null) return "?";
            if (p.contains("/jdymusic/")) return "jdymusic";
            if (p.contains("/ymusic/")) return "ymusic";
            return p.length() > 20 ? p.substring(0, 20) : p;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String[] mirrorsFor(String url) {
        if (url.contains("/jdymusic/")) return MIRRORS_JDY;
        if (url.contains("/ymusic/")) return MIRRORS_YMUSIC;
        return new String[0];
    }

    private static String withHost(String url, String host) {
        try {
            String cur = new URL(url).getHost();
            if (cur == null) return null;
            if (cur.startsWith(host + ".")) return url;
            return url.replace("://" + cur + "/", "://" + host + ".music.126.net/");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 探测地址是否**真的**能播。
     * 不能只看状态码：m804/m704 这类节点常返回 200 + HTML 错误页，
     * 状态码判断会误判为可用，MediaPlayer 拿到后只会报 MEDIA_ERROR_UNSUPPORTED(-38)。
     * 所以取前 16 字节校验音频魔数。
     */
    static boolean probeOk(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestProperty("Range", "bytes=0-15");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                return false;
            }
            byte[] head = new byte[16];
            int n = 0;
            try (InputStream in = c.getInputStream()) {
                while (n < head.length) {
                    int r = in.read(head, n, head.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            boolean ok = looksLikeAudio(head, n);
            if (!ok) {
            }
            return ok;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 常见的音频容器魔数；HTML/JSON/纯文本一律判失败。 */
    static boolean looksLikeAudio(byte[] b, int n) {
        if (n < 4) return false;
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return true;   // FLAC
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;                  // MP3 + ID3
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return true;             // MPEG 帧同步
        if (n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true; // MP4/M4A
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return true;   // OGG
        return false;
    }

    private static String hexHead(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, 8); i++) {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    // --------------------------------------------------------- 解析

    private static List<Song> parseSongs(JSONArray arr) {
        List<Song> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(parseSong(o));
        }
        return out;
    }

    static Song parseSong(JSONObject o) {
        Song s = new Song();
        s.id = o.optLong("id");
        s.name = o.optString("name", "");

        JSONArray ar = o.optJSONArray("artists");
        if (ar == null) ar = o.optJSONArray("ar");
        StringBuilder ab = new StringBuilder();
        if (ar != null) {
            for (int j = 0; j < ar.length(); j++) {
                JSONObject a = ar.optJSONObject(j);
                if (a == null) continue;
                if (ab.length() > 0) ab.append(" / ");
                ab.append(a.optString("name", ""));
            }
        }
        s.artist = ab.toString();

        JSONObject al = o.optJSONObject("album");
        if (al == null) al = o.optJSONObject("al");
        if (al != null) {
            s.album = al.optString("name", "");
            s.picUrl = al.optString("picUrl", "");
        }

        long dur = o.optLong("duration");
        if (dur == 0) dur = o.optLong("dt");
        s.duration = dur;

        s.fee = o.optInt("fee");
        return s;
    }

    // --------------------------------------------------------- 搜索

    /** 单曲搜索。type=1 单曲；返回结构里的 songs 与歌单接口同构，可直接复用 parseSongs。 */
    public List<Song> searchSongs(String keyword, int offset, int limit) throws Exception {
        JSONObject d = request("/api/search/get",
                form("s", keyword, "type", "1",
                        "offset", String.valueOf(offset),
                        "limit", String.valueOf(limit)));
        JSONObject res = d.optJSONObject("result");
        if (res == null) return new ArrayList<>();
        List<Song> out = parseSongs(res.optJSONArray("songs"));
        if (out.isEmpty()) return out;

        // 搜索结果里的 album 只有数值 picId，拼不出 CDN 地址（URL 第一段是加密串）。
        // 用 song/detail 批量补一次真实封面；失败也不影响搜索结果本身。
        JSONArray ids = new JSONArray();
        for (Song s : out) ids.put(s.id);
        try {
            JSONObject det = request("/api/song/detail", form("ids", ids.toString()));
            JSONArray ss = det.optJSONArray("songs");
            if (ss != null) {
                Map<Long, String> pics = new HashMap<>();
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject o = ss.optJSONObject(i);
                    if (o == null) continue;
                    JSONObject al = o.optJSONObject("album");
                    if (al == null) continue;
                    String u = al.optString("picUrl", "");
                    if (!u.isEmpty()) pics.put(o.optLong("id"), u);
                }
                for (Song s : out) {
                    String u = pics.get(s.id);
                    if (u != null) s.picUrl = u;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // --------------------------------------------------------- 推荐页

    /** 推荐页里的一张卡片（歌单或榜单）。 */
    public static class Playlist {
        public long id;
        public String name = "";
        public String cover = "";
        public long playCount;
        public String note = "";
        /** 5 = 我喜欢的音乐（红心歌单），首页「我的歌单」要排除它 */
        public int specialType;
    }

    /** 我的歌单（含红心，调用方自行按 specialType 过滤）。 */
    public List<Playlist> userPlaylists(long uid) throws Exception {
        JSONObject d = request("/api/user/playlist",
                form("uid", String.valueOf(uid), "limit", "100", "offset", "0"));
        return parsePlaylists(d.optJSONArray("playlist"), "coverImgUrl");
    }

    /** 推荐歌单（首页「推荐歌单」横滑，默认取 6 个）。 */
    public List<Playlist> recommendPlaylists(int limit) throws Exception {
        JSONObject d = request("/api/personalized/playlist",
                form("limit", String.valueOf(limit)));
        return parsePlaylists(d.optJSONArray("result"), "picUrl");
    }

    /** 每日推荐歌单（即「私人雷达」）。 */
    public List<Playlist> radarPlaylists() throws Exception {
        JSONObject d = request("/api/v1/discovery/recommend/resource", form());
        return parsePlaylists(d.optJSONArray("recommend"), "picUrl");
    }

    /** 排行榜列表（飙升榜 / 新歌榜 / 原创榜 …）。 */
    public List<Playlist> toplists() throws Exception {
        JSONObject d = request("/api/toplist", form());
        return parsePlaylists(d.optJSONArray("list"), "coverImgUrl");
    }

    /** 推荐新歌（「每周新热趋势」）。 */
    public List<Song> newSongs(int limit) throws Exception {
        JSONObject d = request("/api/personalized/newsong",
                form("limit", String.valueOf(limit)));
        JSONArray arr = d.optJSONArray("result");
        List<Song> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            JSONObject so = it.optJSONObject("song");
            if (so == null) so = it;   // 少数情况下直接是歌曲对象
            Song s = parseSong(so);
            if (s.picUrl == null || s.picUrl.isEmpty()) s.picUrl = it.optString("picUrl", "");
            if (s.name == null || s.name.isEmpty()) s.name = it.optString("name", "");
            out.add(s);
        }
        return out;
    }

    private static List<Playlist> parsePlaylists(JSONArray arr, String coverKey) {
        List<Playlist> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Playlist p = new Playlist();
            p.id = o.optLong("id");
            p.name = o.optString("name", "");
            p.cover = o.optString(coverKey, "");
            if (p.cover.isEmpty()) p.cover = o.optString("picUrl", "");
            p.playCount = o.optLong("playCount");
            p.specialType = o.optInt("specialType");
            // org.json 的 optString 会把 JSON null 变成字符串 "null"，要过滤掉
            String freq = o.optString("updateFrequency", "");
            if ("null".equals(freq)) freq = "";
            int tracks = o.optInt("trackCount");
            if (!freq.isEmpty()) p.note = freq;
            else if (tracks > 0) p.note = tracks + " 首";
            out.add(p);
        }
        return out;
    }

    /**
     * 把网络异常翻译成给用户看的话。
     * UnknownHostException / ConnectException 这些原始信息是
     * "Unable to resolve host ..." 之类的英文堆栈，直接抛给用户没有意义。
     */
    public static String friendlyError(Throwable t) {
        if (t == null) return "未知错误";
        if (t instanceof java.net.SocketTimeoutException) return "网络超时，请稍后重试";
        if (t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.NoRouteToHostException
                || t instanceof java.net.PortUnreachableException
                || t instanceof java.net.SocketException
                || t instanceof javax.net.ssl.SSLException) {
            return "请检查网络连接";
        }
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    static String fmtDuration(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
