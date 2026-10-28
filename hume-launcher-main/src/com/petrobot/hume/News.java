package com.petrobot.hume;

import android.content.Context;
import android.content.SharedPreferences;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * News for the top shelf from Sri Lankan newsrooms and world newsrooms (RSS, free, no key).
 * Sri Lanka stories come first and make up most of the shelf; world stories are mixed in.
 * Each story becomes a carousel item: headline, summary, image, section, source and age.
 */
final class News {
    static final String K_SOURCES = "news_sources_lk";
    static final String LOCAL = "local", WORLD = "world";
    static final String[] SECTIONS = {LOCAL, WORLD};

    /** A newsroom and its Sri Lanka / world feeds (null = none). */
    static final class Source {
        final String id, name, local, world;

        Source(String id, String name, String local, String world) {
            this.id = id;
            this.name = name;
            this.local = local;
            this.world = world;
        }
    }

    static final List<Source> SOURCES = new ArrayList<>();

    static {
        SOURCES.add(new Source("derana", "Ada Derana", "https://www.adaderana.lk/rss.php", null));
        SOURCES.add(new Source("newswire", "Newswire", "https://www.newswire.lk/feed/", null));
        SOURCES.add(new Source("economynext", "EconomyNext", "https://economynext.com/feed/", null));
        SOURCES.add(new Source("island", "The Island", "https://island.lk/feed/", null));
        SOURCES.add(new Source("gazette", "Colombo Gazette", "https://colombogazette.com/feed/", null));
        SOURCES.add(new Source("bbc", "BBC News", null, "https://feeds.bbci.co.uk/news/world/rss.xml"));
        SOURCES.add(new Source("guardian", "The Guardian", null, "https://www.theguardian.com/world/rss"));
        SOURCES.add(new Source("dw", "DW", null, "https://rss.dw.com/rdf/rss-en-world"));
    }

    /** Always Sri Lanka (kept so the settings row has something to show). */
    static String city(Context c) {
        return "Sri Lanka";
    }

    private News() {
    }

    static final String K_REFRESH = "news_refresh_min";
    static final int[] REFRESH_CHOICES = {15, 30, 60};

    /** Minutes between news updates (30 by default). */
    static int refreshMinutes(Context c) {
        return Suggestions.prefs(c).getInt(K_REFRESH, 30);
    }

    static Set<String> sources(Context c) {
        Set<String> def = new HashSet<>(java.util.Arrays.asList("derana", "newswire", "economynext", "island", "gazette", "bbc", "guardian"));
        return new HashSet<>(Suggestions.prefs(c).getStringSet(K_SOURCES, def));
    }

    static void setSources(Context c, Set<String> ids) {
        SharedPreferences p = Suggestions.prefs(c);
        p.edit().putStringSet(K_SOURCES, ids).apply();
    }

    /**
     * Latest stories: mostly Sri Lanka (two rounds of local stories for every round of world
     * stories), alternating between newsrooms. Blocking.
     */
    static List<Suggestions.Item> fetch(Context c, int max) {
        List<List<Suggestions.Item>> local = new ArrayList<>(), world = new ArrayList<>();
        Set<String> chosen = sources(c);
        for (Source s : SOURCES) {
            if (!chosen.contains(s.id)) continue;
            for (String sec : SECTIONS) {
                String url = sec.equals(LOCAL) ? s.local : s.world;
                if (url == null) continue;
                String label = sec.equals(LOCAL) ? "Sri Lanka" : "World";
                try {
                    long maxAge = (sec.equals(LOCAL) ? 3 : 2) * 86400000L;
                    List<Suggestions.Item> l = read(url, label + "  \u00b7  " + s.name, maxAge);
                    if (!l.isEmpty()) (sec.equals(LOCAL) ? local : world).add(l);
                } catch (Exception e) {
                    android.util.Log.w("TvHome", "news feed " + url + ": " + e);
                }
            }
        }
        List<Suggestions.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            List<List<Suggestions.Item>> pick = new ArrayList<>(local);
            if (round % 2 == 0) pick.addAll(world);
            for (List<Suggestions.Item> l : pick) {
                int idx = round;
                if (idx < l.size()) {
                    any = true;
                    Suggestions.Item it = l.get(idx);
                    if (out.size() < max && seen.add(it.title.toLowerCase(Locale.ROOT))) out.add(it);
                }
            }
            if (!any && round >= 16) break;  // 16 rounds is more than any feed gives
            if (round >= 40) break;
        }
        return out;
    }

    private static List<Suggestions.Item> read(String url, String label, long maxAge) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) HumeLauncher");
        List<Suggestions.Item> out = new ArrayList<>();
        try (InputStream in = con.getInputStream()) {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            x.setInput(in, null);
            Suggestions.Item cur = null;
            String tag = null, rawDesc = "";
            int seenItems = 0;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < 30 && seenItems < 80; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = x.getName();
                    if (tag.equals("item")) {
                        seenItems++;
                        cur = new Suggestions.Item();
                        cur.type = "news";
                        cur.title = cur.overview = cur.backdrop = cur.year = "";
                        cur.genres = label;
                        cur.logo = "";
                        rawDesc = "";
                    } else if (cur != null && (tag.equals("media:content") || tag.equals("media:thumbnail") || tag.equals("enclosure"))) {
                        String u = x.getAttributeValue(null, "url");
                        String type = x.getAttributeValue(null, "type");
                        String medium = x.getAttributeValue(null, "medium");
                        if (medium != null && !medium.equals("image")) u = null;
                        if (u != null && (type == null || type.startsWith("image")) && cur.backdrop.isEmpty()) cur.backdrop = bigger(u);
                    }
                } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                    String t = x.getText();
                    switch (tag) {
                        case "title": cur.title += t; break;
                        case "link": cur.link += t.trim(); break;
                        case "description": cur.overview += t; rawDesc += t; break;
                        case "content:encoded": rawDesc += t; break;
                        case "pubDate": cur.published = parseDate(t.trim()); break;
                        default: break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (x.getName().equals("item") && cur != null) {
                        cur.title = clean(cur.title);
                        cur.overview = clean(cur.overview);
                        if (cur.backdrop.isEmpty()) {  // some feeds (India Today) put the image in the summary
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']").matcher(rawDesc);
                            if (m.find()) cur.backdrop = bigger(m.group(1).replace("&amp;", "&"));
                        }
                        boolean fresh = cur.published == 0 || System.currentTimeMillis() - cur.published < maxAge;
                        if (!cur.title.isEmpty() && !cur.backdrop.isEmpty() && fresh) out.add(cur);
                        cur = null;
                    }
                    tag = null;
                }
            }
        } finally {
            con.disconnect();
        }
        out.sort((a, b) -> Long.compare(b.published, a.published));  // newest first
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    /** Ask for a larger rendition where the newsroom's image URLs allow it. */
    private static String bigger(String u) {
        java.util.regex.Matcher toi = java.util.regex.Pattern.compile("toiimg\\.com/photo/msid-(\\d+)").matcher(u);
        if (toi.find()) return "https://static.toiimg.com/thumb/msid-" + toi.group(1) + ",width-1280,height-720,resizemode-4/photo.jpg";
        u = u.replaceAll("ichef\\.bbci\\.co\\.uk/(ace/standard|news)/\\d+/", "ichef.bbci.co.uk/$1/976/");
        u = u.replaceAll("(i\\.guim\\.co\\.uk/.*?)width=\\d+", "$1width=1200");
        return u;
    }

    /** Strip tags and entities; keep one short paragraph. */
    static String clean(String s) {
        String t = s.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 260 ? t.substring(0, 257).trim() + "\u2026" : t;
    }

    /** RSS dates: RFC 822 ("Thu, 22 Oct 2026 13:33:54 +0530") or ISO 8601 (Times of India). */
    static long parseDate(String pub) {
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"};
        for (String f : fmts) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(pub).getTime();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return 0;
    }

    /** "Just now", "5 min ago", "3 h ago", "Yesterday" \u2014 worked out when shown, so it never goes stale. */
    static String age(long published) {
        if (published <= 0) return "";
        long min = (System.currentTimeMillis() - published) / 60000;
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        return min < 48 * 60 ? "Yesterday" : (min / 1440) + " days ago";
    }
}
