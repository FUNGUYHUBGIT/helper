package fr.entarsh.helper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/* Notifications de Helper, même appli fermée (vérification toutes les heures) :
   - le soir (à partir de 18 h) : le nombre de devoirs à faire pour le lendemain ;
   - les demandes envoyées à cette personne précisément (et les réponses dans ces demandes).
   Le téléphone interroge Helper avec une clé propre au compte (aucun mot de passe n'est gardé). */
public class Notifs extends Worker {
  static final String PREFS = "notifs";
  static final String CANAL_DEVOIRS = "devoirs", CANAL_DEMANDES = "demandes";

  public Notifs(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
  static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
  static Constraints contraintes() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }

  static void configurer(Context c, String json) {
    try {
      JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
      if (o.optString("compte", "").isEmpty() || o.optString("cle", "").isEmpty()) { arreter(c); return; }
      SharedPreferences p = prefs(c); boolean nouveau = !o.optString("compte").equals(p.getString("compte", ""));
      SharedPreferences.Editor e = p.edit().putString("compte", o.optString("compte")).putString("cle", o.optString("cle"))
        .putBoolean("devoirs", o.optBoolean("devoirs", true)).putBoolean("demandes", o.optBoolean("demandes", true));
      if (nouveau) e.remove("dernierMsg").remove("jourDevoirs");
      e.apply();
      PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(Notifs.class, 1, TimeUnit.HOURS).setConstraints(contraintes()).build();
      WorkManager.getInstance(c).enqueueUniquePeriodicWork("notifs", ExistingPeriodicWorkPolicy.UPDATE, r);
      if (nouveau) WorkManager.getInstance(c).enqueueUniqueWork("notifs-maintenant", ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(Notifs.class).setConstraints(contraintes()).build());
    } catch (Exception x) {}
  }
  static void arreter(Context c) { WorkManager.getInstance(c).cancelUniqueWork("notifs"); prefs(c).edit().clear().apply(); }

  static void canaux(Context c) {
    if (Build.VERSION.SDK_INT < 26) return;
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel(CANAL_DEVOIRS, "Devoirs pour demain", NotificationManager.IMPORTANCE_DEFAULT));
    nm.createNotificationChannel(new NotificationChannel(CANAL_DEMANDES, "Demandes qui me sont adressées", NotificationManager.IMPORTANCE_HIGH));
  }
  void montrer(Context c, int id, String canal, String titre, String texte, String vue) {
    if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
    canaux(c);
    Intent i = new Intent(c, AppActivity.class).putExtra("vue", vue).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, canal) : new android.app.Notification.Builder(c);
    b.setSmallIcon(R.drawable.ic_notif).setContentTitle(titre).setContentText(texte).setStyle(new android.app.Notification.BigTextStyle().bigText(texte))
      .setAutoCancel(true).setContentIntent(pi).setColor(0xFFF2AE02);
    c.getSystemService(NotificationManager.class).notify(id, b.build());
  }

  @NonNull @Override public Result doWork() {
    Context c = getApplicationContext(); SharedPreferences p = prefs(c);
    String compte = p.getString("compte", ""), cle = p.getString("cle", "");
    if (compte.isEmpty() || cle.isEmpty()) return Result.success();
    try {
      java.util.HashMap<String, String> hs = new java.util.HashMap<>();
      hs.put("apikey", SyncDevoirs.SB_CLE); hs.put("Authorization", "Bearer " + SyncDevoirs.SB_CLE); hs.put("Content-Type", "application/json");
      JSONObject arg = new JSONObject(); arg.put("p_compte", compte); arg.put("p_cle", cle);
      SyncDevoirs.Rep r = SyncDevoirs.http("POST", SyncDevoirs.SB_URL + "/rest/v1/rpc/notifs_helper", hs, arg.toString());
      if (r.st != 200 || r.corps == null || r.corps.trim().equals("null")) return Result.success();
      JSONObject j = new JSONObject(r.corps);
      // 1. devoirs pour demain : une seule notification par soir, à partir de 18 h (après la synchronisation de 17 h 30)
      Calendar h = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
      String jour = String.format(java.util.Locale.US, "%tF", h);
      if (p.getBoolean("devoirs", true) && h.get(Calendar.HOUR_OF_DAY) >= 18 && !jour.equals(p.getString("jourDevoirs", ""))) {
        int n = j.optInt("devoirs", 0);
        if (n > 0) montrer(c, 1, CANAL_DEVOIRS, n == 1 ? "1 devoir pour demain" : n + " devoirs pour demain", n == 1 ? "Tu as 1 devoir à faire pour demain." : "Tu as " + n + " devoirs à faire pour demain.", "devoirs");
        p.edit().putString("jourDevoirs", jour).apply();
      }
      // 2. demandes envoyées à cette personne : on prévient pour chaque nouveau message
      JSONArray m = j.optJSONArray("messages"); String dernier = p.getString("dernierMsg", ""), plusRecent = dernier;
      if (m != null) {
        boolean premiere = dernier.isEmpty(); int montres = 0;
        for (int i = m.length() - 1; i >= 0; i--) {          // du plus ancien au plus récent
          JSONObject x = m.getJSONObject(i); String le = x.optString("le", "");
          if (le.compareTo(plusRecent) > 0) plusRecent = le;
          if (premiere || le.compareTo(dernier) <= 0 || !p.getBoolean("demandes", true)) continue;
          if (montres++ >= 4) continue;
          String titre = (x.optBoolean("reponse") ? "Réponse de " : "Demande de ") + x.optString("deNom", "quelqu'un");
          String texte = (x.optString("objet", "").isEmpty() ? "" : x.optString("objet") + " — ") + x.optString("corps", "");
          montrer(c, 100 + (x.optString("id").hashCode() & 0xffff), CANAL_DEMANDES, titre, texte, "demandes");
        }
      }
      if (!plusRecent.equals(dernier)) p.edit().putString("dernierMsg", plusRecent).apply();
    } catch (Exception e) {}
    return Result.success();
  }
}
