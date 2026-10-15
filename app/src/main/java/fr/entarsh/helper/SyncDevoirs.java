package fr.entarsh.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/* Synchronisation des devoirs EcoleDirecte en arrière-plan (toutes les 3 h, même appli fermée).
   La connexion part du téléphone : même adresse IP que d'habitude, donc pas de blocage d'EcoleDirecte. */
public class SyncDevoirs extends Worker {
  static final String SB_URL = "https://hltchrwrefrrfhtzimkn.supabase.co";
  static final String SB_CLE = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhsdGNocndyZWZycmZodHppbWtuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEyMTUzMjMsImV4cCI6MjEwNjc5MTMyM30.aXJkkgIz89wsDw_7HrxesbvopPrkFleVfMVLB7X9GkM";
  static final String ED = "https://api.ecoledirecte.com/v3", V = "4.97.2";
  static final String PREFS = "sync-devoirs";

  public SyncDevoirs(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

  static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
  static Constraints contraintes() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }
  static void planifier(Context c, boolean remplacer) {
    PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(SyncDevoirs.class, 3, TimeUnit.HOURS).setConstraints(contraintes()).build();
    WorkManager.getInstance(c).enqueueUniquePeriodicWork("sync-devoirs", remplacer ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, r);
  }
  static void maintenant(Context c) {
    WorkManager.getInstance(c).enqueueUniqueWork("sync-devoirs-maintenant", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(SyncDevoirs.class).setConstraints(contraintes()).build());
  }
  static void arreter(Context c) { WorkManager.getInstance(c).cancelUniqueWork("sync-devoirs"); prefs(c).edit().clear().apply(); }

  /* ---------- HTTP ---------- */
  static class Rep { int st; String corps; Map<String, List<String>> h; }
  static String lire(InputStream in) throws Exception { if (in == null) return ""; ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); in.close(); return o.toString("UTF-8"); }
  static Rep http(String methode, String url, Map<String, String> h, String corps) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setRequestMethod(methode);
    for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
    if (corps != null) { byte[] b = corps.getBytes("UTF-8"); c.setDoOutput(true); c.setFixedLengthStreamingMode(b.length); OutputStream o = c.getOutputStream(); o.write(b); o.close(); }
    Rep r = new Rep(); r.st = c.getResponseCode(); r.h = c.getHeaderFields(); r.corps = lire(r.st >= 400 ? c.getErrorStream() : c.getInputStream()); c.disconnect(); return r;
  }
  static String entete(Rep r, String nom) { for (Map.Entry<String, List<String>> e : r.h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(nom) && !e.getValue().isEmpty()) return e.getValue().get(0); return null; }

  /* ---------- EcoleDirecte ---------- */
  String ua, token = "", gtk = "", cookies = "", f2a = "";
  JSONObject appelED(String chemin, JSONObject data) throws Exception {
    java.util.HashMap<String, String> h = new java.util.HashMap<>();
    h.put("Content-Type", "application/x-www-form-urlencoded"); h.put("User-Agent", ua);
    if (!token.isEmpty()) h.put("X-Token", token); if (!f2a.isEmpty()) h.put("2fa-Token", f2a);
    if (!gtk.isEmpty()) h.put("X-Gtk", gtk); if (!cookies.isEmpty()) h.put("Cookie", cookies);
    Rep r = http("POST", ED + chemin + (chemin.contains("?") ? "&" : "?") + "v=" + V, h, "data=" + URLEncoder.encode(data.toString(), "UTF-8"));
    JSONObject j = new JSONObject(r.corps);
    String tk = entete(r, "x-token"); if (tk == null || tk.isEmpty()) tk = j.optString("token", ""); if (!tk.isEmpty()) token = tk;
    String fa = entete(r, "2fa-token"); if (fa != null && !fa.isEmpty()) f2a = fa;
    return j;
  }
  JSONObject login(SharedPreferences p, boolean encoder) throws Exception {
    java.util.HashMap<String, String> h = new java.util.HashMap<>(); h.put("User-Agent", ua);
    Rep g = http("GET", ED + "/login.awp?gtk=1&v=" + V, h, null);
    List<String> paires = new ArrayList<>(); gtk = "";
    for (Map.Entry<String, List<String>> e : g.h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie"))
      for (String v : e.getValue()) { String pr = v.split(";")[0].trim(); if (pr.contains("=")) { paires.add(pr); if (pr.startsWith("GTK=")) gtk = pr.substring(4); } }
    cookies = android.text.TextUtils.join("; ", paires);
    if (gtk.isEmpty()) throw new Exception("EcoleDirecte n'a pas donné de jeton de connexion.");
    token = ""; f2a = "";
    String id = p.getString("edId", ""), mdp = p.getString("edMdp", "");
    JSONObject d = new JSONObject(); d.put("identifiant", encoder ? URLEncoder.encode(id, "UTF-8").replace("+", "%20") : id); d.put("motdepasse", encoder ? URLEncoder.encode(mdp, "UTF-8").replace("+", "%20") : mdp);
    d.put("isReLogin", false); d.put("uuid", ""); JSONArray fa = new JSONArray();
    if (!p.getString("faCn", "").isEmpty()) { JSONObject f = new JSONObject(); f.put("cn", p.getString("faCn", "")); f.put("cv", p.getString("faCv", "")); fa.put(f); }
    d.put("fa", fa);
    return appelED("/login.awp", d);
  }
  static String b64(String s) { try { return new String(Base64.decode(s == null ? "" : s, Base64.DEFAULT), "UTF-8"); } catch (Exception e) { return ""; } }
  @SuppressWarnings("deprecation")
  static String texte(String html) {
    Matcher m = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html == null ? "" : html);
    StringBuffer sb = new StringBuffer();
    while (m.find()) { String t = m.group(2).replaceAll("<[^>]+>", "").trim(), u = m.group(1); m.appendReplacement(sb, Matcher.quoteReplacement(!t.isEmpty() && !t.equals(u) ? t + " (" + u + ")" : u)); }
    m.appendTail(sb);
    String t = Html.fromHtml(sb.toString().replaceAll("(?i)<li[^>]*>", "<br>• ").replaceAll("(?i)</li>", "")).toString();
    return t.replace(' ', ' ').replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
  }

  @NonNull @Override public Result doWork() {
    Context c = getApplicationContext(); SharedPreferences p = prefs(c);
    if (p.getString("edId", "").isEmpty()) return Result.success();
    ua = p.getString("ua", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
    try {
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE); f.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
      String auj = f.format(Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris")).getTime());
      // 1. Helper (compte du gérant)
      java.util.HashMap<String, String> hs = new java.util.HashMap<>(); hs.put("apikey", SB_CLE); hs.put("Content-Type", "application/json");
      JSONObject id = new JSONObject(); id.put("email", p.getString("email", "")); id.put("password", p.getString("mdp", ""));
      Rep a = http("POST", SB_URL + "/auth/v1/token?grant_type=password", hs, id.toString());
      if (a.st != 200) throw new Exception("Connexion à Helper refusée : réactive la synchronisation automatique.");
      String jeton = new JSONObject(a.corps).getString("access_token"); hs.put("Authorization", "Bearer " + jeton);
      // 2. EcoleDirecte
      String mdp = p.getString("edMdp", "");
      JSONObject r = login(p, false);
      if (r.optInt("code") == 505 && mdp.matches(".*[%+&].*")) r = login(p, true);
      if (r.optInt("code") == 505) throw new Exception("EcoleDirecte refuse la connexion : " + r.optString("message", "mot de passe incorrect"));
      if (r.optInt("code") == 250) throw new Exception("EcoleDirecte pose une question de sécurité : appuie sur « Synchroniser » dans l'onglet Devoirs pour y répondre.");
      if (r.optInt("code") != 200) throw new Exception("Connexion EcoleDirecte refusée (code " + r.optInt("code") + ").");
      JSONArray comptes = r.getJSONObject("data").optJSONArray("accounts"); long eleve = 0;
      for (int i = 0; comptes != null && i < comptes.length(); i++) { JSONObject cp = comptes.getJSONObject(i); if ("E".equals(cp.optString("typeCompte"))) { eleve = cp.getLong("id"); break; } }
      if (eleve == 0 && comptes != null && comptes.length() > 0) { JSONArray el = comptes.getJSONObject(0).optJSONObject("profile") != null ? comptes.getJSONObject(0).getJSONObject("profile").optJSONArray("eleves") : null; if (el != null && el.length() > 0) eleve = el.getJSONObject(0).getLong("id"); }
      if (eleve == 0) throw new Exception("Aucun compte élève trouvé sur EcoleDirecte.");
      JSONObject liste = appelED("/Eleves/" + eleve + "/cahierdetexte.awp?verbe=get", new JSONObject());
      if (liste.optInt("code") != 200) throw new Exception("Lecture du cahier de texte impossible (code " + liste.optInt("code") + ").");
      List<String> dates = new ArrayList<>();
      Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
      for (int k = 7; k >= 1; k--) { Calendar x = (Calendar) cal.clone(); x.add(Calendar.DAY_OF_MONTH, -k); int w = x.get(Calendar.DAY_OF_WEEK); if (w != Calendar.SATURDAY && w != Calendar.SUNDAY) dates.add(f.format(x.getTime())); }
      dates.add(auj);
      JSONObject ld = liste.optJSONObject("data"); List<String> av = new ArrayList<>();
      if (ld != null) { java.util.Iterator<String> it = ld.keys(); while (it.hasNext()) { String d = it.next(); if (d.matches("\\d{4}-\\d{2}-\\d{2}")) av.add(d); } }
      Collections.sort(av); for (String d : av.subList(0, Math.min(30, av.size()))) if (!dates.contains(d)) dates.add(d);
      JSONArray nouveaux = new JSONArray();
      for (String date : dates) {
        Thread.sleep(300);
        JSONObject j = appelED("/Eleves/" + eleve + "/cahierdetexte/" + date + ".awp?verbe=get", new JSONObject());
        if (j.optInt("code") != 200 || j.optJSONObject("data") == null) continue;
        JSONArray mats = j.getJSONObject("data").optJSONArray("matieres");
        for (int i = 0; mats != null && i < mats.length(); i++) {
          JSONObject m = mats.getJSONObject(i), af = m.optJSONObject("aFaire"); if (af == null) continue;
          JSONObject d = new JSONObject();
          d.put("id", "ed-" + (af.has("idDevoir") ? af.opt("idDevoir") : m.opt("id"))); d.put("date", date); d.put("matiere", m.optString("matiere", ""));
          d.put("prof", m.optString("nomProf", "").trim()); d.put("groupe", "G".equals(m.optString("entityType")) ? m.optString("entityLibelle", "") : "");
          d.put("interrogation", m.optBoolean("interrogation", false)); d.put("donneLe", af.optString("donneLe", ""));
          String t = texte(b64(af.optString("contenu", ""))); d.put("contenu", t.length() > 4000 ? t.substring(0, 4000) : t);
          JSONArray docs = new JSONArray(), src = af.optJSONArray("documents");
          for (int k = 0; src != null && k < src.length() && k < 10; k++) { String l = src.getJSONObject(k).optString("libelle", ""); if (!l.isEmpty()) docs.put(l); }
          d.put("documents", docs); nouveaux.put(d);
        }
      }
      // 3. fusion avec les devoirs déjà enregistrés, puis enregistrement dans Helper
      Rep ex = http("GET", SB_URL + "/rest/v1/docs?select=data&path=eq.edt/devoirs", hs, null);
      Set<String> vus = new HashSet<>(dates); Calendar lim = (Calendar) cal.clone(); lim.add(Calendar.DAY_OF_MONTH, -120); String limite = f.format(lim.getTime());
      List<JSONObject> tous = new ArrayList<>();
      try { JSONArray rows = new JSONArray(ex.corps); if (rows.length() > 0) { JSONArray anc = rows.getJSONObject(0).getJSONObject("data").optJSONArray("devoirs");
        for (int i = 0; anc != null && i < anc.length(); i++) { JSONObject d = anc.getJSONObject(i); String dt = d.optString("date"); if (!vus.contains(dt) && dt.compareTo(limite) >= 0) tous.add(d); } } } catch (Exception ignore) {}
      for (int i = 0; i < nouveaux.length(); i++) tous.add(nouveaux.getJSONObject(i));
      Collections.sort(tous, (x, y) -> x.optString("date").compareTo(y.optString("date")));
      JSONArray finale = new JSONArray(); for (int i = Math.max(0, tous.size() - 600); i < tous.size(); i++) finale.put(tous.get(i));
      SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
      String maintenant = iso.format(new java.util.Date());
      JSONObject data = new JSONObject(); data.put("source", "ecoledirecte"); data.put("majLe", maintenant); data.put("par", "appli (arrière-plan)"); data.put("devoirs", finale);
      JSONObject ligne = new JSONObject(); ligne.put("path", "edt/devoirs"); ligne.put("col", "edt"); ligne.put("data", data);
      hs.put("Prefer", "resolution=merge-duplicates,return=minimal");
      Rep w = http("POST", SB_URL + "/rest/v1/docs", hs, ligne.toString());
      if (w.st >= 300) throw new Exception("Enregistrement dans Helper refusé (HTTP " + w.st + ").");
      p.edit().putString("dernier", maintenant).putBoolean("ok", true).putString("message", nouveaux.length() + " devoir(s) lu(s) sur " + dates.size() + " jours.").apply();
      return Result.success();
    } catch (Exception e) {
      SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); iso.setTimeZone(TimeZone.getTimeZone("UTC"));
      p.edit().putString("dernier", iso.format(new java.util.Date())).putBoolean("ok", false).putString("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
      return Result.success();
    }
  }
}
