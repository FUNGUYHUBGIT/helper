package fr.entarsh.helper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class AppActivity extends Activity {
  static final String ACCUEIL = "https://funguyhubgit.github.io/helper/?app=android";
  static final int CHOIX_FICHIER = 7;
  WebView web;
  TextToSpeech tts;
  boolean ttsPret = false;
  String scriptVoix = "";
  ValueCallback<Uri[]> rappelFichier;

  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  @Override protected void onCreate(Bundle etat) {
    super.onCreate(etat);
    web = new WebView(this);
    web.setBackgroundColor(Color.parseColor("#0E0E0F"));
    setContentView(web);

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setTextZoom(100);
    s.setUserAgentString(s.getUserAgentString() + " HelperApp/1.2");

    // Aucune inversion / assombrissement automatique : on garde les vraies couleurs de Helper
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
      WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
      WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_OFF);
    if (Build.VERSION.SDK_INT >= 29) web.setForceDarkAllowed(false);

    // Voix de lecture (dictée) : on branche la synthèse vocale d'Android
    try { InputStream in = getAssets().open("voix.js"); byte[] b = new byte[in.available()]; in.read(b); in.close(); scriptVoix = new String(b, "UTF-8"); } catch (Exception e) {}
    web.addJavascriptInterface(new PontVoix(), "HelperTTS");
    web.addJavascriptInterface(new PontAppli(), "HelperApp");
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
      WebViewCompat.addDocumentStartJavaScript(web, scriptVoix, Collections.singleton("https://funguyhubgit.github.io"));
    tts = new TextToSpeech(this, st -> {
      if (st != TextToSpeech.SUCCESS) return;
      tts.setLanguage(Locale.FRANCE);
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        @Override public void onStart(String id) { js("window.__ttsDebut&&__ttsDebut('" + id + "')"); }
        @Override public void onDone(String id) { js("window.__ttsFin&&__ttsFin('" + id + "',true)"); }
        @Override public void onError(String id) { js("window.__ttsFin&&__ttsFin('" + id + "',false)"); }
      });
      ttsPret = true;
      js("window.__ttsVoix&&__ttsVoix()");
    });

    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return externe(r.getUrl()); }
      @SuppressWarnings("deprecation")
      @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return externe(Uri.parse(url)); }
      @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(scriptVoix, null);
      }
      @Override public void onPageFinished(WebView v, String url) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(scriptVoix, null);
      }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame()) v.loadDataWithBaseURL(null,
          "<html><body style='background:#0E0E0F;color:#eee;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center'>"
          + "<h2>Pas de connexion</h2><p>Helper a besoin d'internet.</p>"
          + "<a href='" + ACCUEIL + "' style='margin-top:16px;padding:12px 22px;border-radius:12px;background:#7c5cff;color:#fff;text-decoration:none'>Réessayer</a></body></html>",
          "text/html", "UTF-8", null);
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
        if (rappelFichier != null) rappelFichier.onReceiveValue(null);
        rappelFichier = cb;
        try { startActivityForResult(p.createIntent(), CHOIX_FICHIER); }
        catch (ActivityNotFoundException e) { rappelFichier = null; return false; }
        return true;
      }
    });
    web.setDownloadListener((url, ua, cd, mime, len) -> ouvrir(Uri.parse(url)));

    if (etat != null) web.restoreState(etat);
    else web.loadUrl(ACCUEIL);
  }

  boolean externe(Uri u) {
    String h = u.getHost() == null ? "" : u.getHost(), p = u.getPath() == null ? "" : u.getPath();
    if ("funguyhubgit.github.io".equals(h) && p.startsWith("/helper")) return false;
    if (h.endsWith("supabase.co")) return false;
    ouvrir(u);
    return true;
  }
  void ouvrir(Uri u) { try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {} }
  void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

  // ----- icône aux couleurs choisies dans les préférences -----
  static final String[] ICONES = {"MainActivity", "IconeOrange", "IconeCorail", "IconeBleu", "IconeVert", "IconeRose", "IconeViolet"};
  static final int[] TEINTES = {0xf2ae02, 0xf5a524, 0xff6b3d, 0x4fa3f7, 0x5cc98a, 0xef6fa6, 0xa585f2};
  volatile String iconeVoulue = null;
  class PontAppli {
    @JavascriptInterface public void icone(String hex) {
      try {
        int c = Integer.parseInt(hex.replace("#", ""), 16), meilleur = 0; long dmin = Long.MAX_VALUE;
        for (int i = 0; i < TEINTES.length; i++) {
          long dr = ((c >> 16) & 255) - ((TEINTES[i] >> 16) & 255), dg = ((c >> 8) & 255) - ((TEINTES[i] >> 8) & 255), db = (c & 255) - (TEINTES[i] & 255);
          long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
          if (d < dmin) { dmin = d; meilleur = i; }
        }
        iconeVoulue = ICONES[meilleur];
      } catch (Exception e) {}
    }
  }
  // appliqué quand on quitte l'appli, pour ne pas la fermer en pleine utilisation
  void appliquerIcone() {
    String v = iconeVoulue; if (v == null) return;
    PackageManager pm = getPackageManager();
    try {
      ComponentName voulu = new ComponentName(this, "fr.entarsh.helper." + v);
      if (pm.getComponentEnabledSetting(voulu) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          || (v.equals("MainActivity") && pm.getComponentEnabledSetting(voulu) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && toutesParDefaut(pm))) return;
      pm.setComponentEnabledSetting(voulu, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
      for (String n : ICONES) if (!n.equals(v))
        pm.setComponentEnabledSetting(new ComponentName(this, "fr.entarsh.helper." + n), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    } catch (Exception e) {}
  }
  boolean toutesParDefaut(PackageManager pm) {
    for (String n : ICONES) if (pm.getComponentEnabledSetting(new ComponentName(this, "fr.entarsh.helper." + n)) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;
    return true;
  }

  class PontVoix {
    @JavascriptInterface public String voix() {
      JSONArray a = new JSONArray();
      if (!ttsPret) return a.toString();
      try {
        Set<Voice> l = tts.getVoices();
        if (l != null) for (Voice v : l) {
          if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
          String lang = v.getLocale().toLanguageTag();
          if (!lang.matches("(?i)^(fr|en|es|de|it).*")) continue;
          JSONObject o = new JSONObject(); o.put("name", v.getName()); o.put("lang", lang); o.put("reseau", v.isNetworkConnectionRequired());
          a.put(o);
        }
      } catch (Exception e) {}
      return a.toString();
    }
    @JavascriptInterface public void parler(String id, String texte, String lang, String nom, double vitesse, double hauteur) {
      if (!ttsPret) { js("window.__ttsFin&&__ttsFin('" + id + "',false)"); return; }
      try {
        Voice choisie = null;
        if (nom != null && !nom.isEmpty() && tts.getVoices() != null)
          for (Voice v : tts.getVoices()) if (v.getName().equals(nom)) { choisie = v; break; }
        if (choisie != null) tts.setVoice(choisie); else tts.setLanguage(Locale.forLanguageTag(lang));
        tts.setSpeechRate((float) vitesse);
        tts.setPitch((float) hauteur);
        Bundle b = new Bundle();
        if (tts.speak(texte, TextToSpeech.QUEUE_ADD, b, id) != TextToSpeech.SUCCESS) js("window.__ttsFin&&__ttsFin('" + id + "',false)");
      } catch (Exception e) { js("window.__ttsFin&&__ttsFin('" + id + "',false)"); }
    }
    @JavascriptInterface public void arreter() { if (ttsPret) tts.stop(); }
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    if (req == CHOIX_FICHIER && rappelFichier != null) {
      rappelFichier.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
      rappelFichier = null;
    } else super.onActivityResult(req, res, data);
  }
  @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
  @SuppressWarnings("deprecation")
  @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
  @Override protected void onPause() { super.onPause(); if (ttsPret) tts.stop(); }
  @Override protected void onStop() { super.onStop(); appliquerIcone(); }
  @Override protected void onDestroy() { if (tts != null) tts.shutdown(); if (web != null) web.destroy(); super.onDestroy(); }
}
