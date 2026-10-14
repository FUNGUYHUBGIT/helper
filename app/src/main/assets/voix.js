(function(){
  if (!window.HelperTTS || window.__helperVoix) return; window.__helperVoix = true;
  var T = window.HelperTTS, file = {}, n = 0, voix = [], ecoute = {voiceschanged: []};
  function charger(){ try { voix = JSON.parse(T.voix() || "[]").map(function(v){ return {name: v.name, lang: v.lang, voiceURI: v.name, localService: !v.reseau, "default": false}; }); } catch(e) { voix = []; } }
  function U(t){ this.text = t == null ? "" : String(t); this.lang = ""; this.voice = null; this.rate = 1; this.pitch = 1; this.volume = 1;
    this.onstart = this.onend = this.onerror = null; this._e = {}; }
  U.prototype.addEventListener = function(t, f){ (this._e[t] = this._e[t] || []).push(f); };
  U.prototype.removeEventListener = function(t, f){ var l = this._e[t] || []; var i = l.indexOf(f); if (i >= 0) l.splice(i, 1); };
  function emettre(u, t, extra){ var ev = {type: t, utterance: u, target: u, charIndex: 0, elapsedTime: 0}; if (extra) ev.error = extra;
    try { if (u["on" + t]) u["on" + t](ev); } catch(e) {} (u._e[t] || []).forEach(function(f){ try { f(ev); } catch(e) {} }); }
  var S = {
    pending: false, paused: false,
    get speaking(){ return Object.keys(file).length > 0; },
    getVoices: function(){ if (!voix.length) charger(); return voix.slice(); },
    speak: function(u){ var id = "u" + (++n); file[id] = u; var v = u.voice ? u.voice.name : "";
      T.parler(id, u.text, u.lang || (u.voice && u.voice.lang) || "fr-FR", v, +u.rate || 1, +u.pitch || 1); },
    cancel: function(){ var ids = Object.keys(file), l = ids.map(function(i){ return file[i]; }); file = {}; T.arreter();
      l.forEach(function(u, i){ emettre(u, "error", i ? "canceled" : "interrupted"); }); },
    pause: function(){}, resume: function(){},
    onvoiceschanged: null,
    addEventListener: function(t, f){ (ecoute[t] = ecoute[t] || []).push(f); },
    removeEventListener: function(t, f){ var l = ecoute[t] || []; var i = l.indexOf(f); if (i >= 0) l.splice(i, 1); },
    dispatchEvent: function(){ return true; }
  };
  window.__ttsDebut = function(id){ var u = file[id]; if (u) emettre(u, "start"); };
  window.__ttsFin = function(id, ok){ var u = file[id]; if (!u) return; delete file[id]; emettre(u, ok ? "end" : "error", ok ? null : "synthesis-failed"); };
  window.__ttsVoix = function(){ charger(); var ev = {type: "voiceschanged"}; try { if (S.onvoiceschanged) S.onvoiceschanged(ev); } catch(e) {}
    (ecoute.voiceschanged || []).forEach(function(f){ try { f(ev); } catch(e) {} }); };
  try { Object.defineProperty(window, "speechSynthesis", {value: S, configurable: true, writable: true}); } catch(e) { window.speechSynthesis = S; }
  try { Object.defineProperty(window, "SpeechSynthesisUtterance", {value: U, configurable: true, writable: true}); } catch(e) { window.SpeechSynthesisUtterance = U; }
  charger();
})();
