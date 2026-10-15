// Robot « Devoirs » : récupère le cahier de texte EcoleDirecte et l'enregistre dans Helper (document edt/devoirs).
// Lancé par GitHub Actions (.github/workflows/devoirs.yml) quelques fois par jour.
// Les identifiants viennent des Secrets du dépôt : rien de personnel n'est écrit dans ce fichier ni dans le journal.
import { createClient } from "@supabase/supabase-js";

const SB_URL = "https://hltchrwrefrrfhtzimkn.supabase.co";
const SB_CLE = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhsdGNocndyZWZycmZodHppbWtuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEyMTUzMjMsImV4cCI6MjEwNjc5MTMyM30.aXJkkgIz89wsDw_7HrxesbvopPrkFleVfMVLB7X9GkM";
const ED = "https://api.ecoledirecte.com/v3";
const VERSIONS = [process.env.ED_VERSION, "4.97.2", "4.98.0", "4.75.0"].filter(Boolean);
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const env = k => { const v = (process.env[k] || "").trim(); if (!v) throw new Error(`Secret manquant : ${k}`); return v; };
const pause = ms => new Promise(r => setTimeout(r, ms));
const b64 = s => Buffer.from(String(s || ""), "base64").toString("utf8");
const versB64 = s => Buffer.from(String(s), "utf8").toString("base64");
const norm = s => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\s+/g, " ").trim();

/* ---------- Helper (Supabase) : connexion avec le compte du gérant ---------- */
const sb = createClient(SB_URL, SB_CLE, {auth: {persistSession: false, autoRefreshToken: false}});
async function connecterHelper(){
  const r = await sb.rpc("login_email", {p_id: env("HELPER_PSEUDO").toLowerCase()});
  if (r.error || !r.data || !r.data.email) throw new Error("Compte Helper introuvable (secret HELPER_PSEUDO).");
  const s = await sb.auth.signInWithPassword({email: r.data.email, password: env("HELPER_MDP")});
  if (s.error) throw new Error("Connexion à Helper refusée (secret HELPER_MDP).");
}
const lire = async path => { const {data, error} = await sb.from("docs").select("data").eq("path", path).maybeSingle(); if (error) throw new Error("Lecture Helper : " + error.message); return data ? data.data : null; };
const ecrire = async (path, data) => { const {error} = await sb.from("docs").upsert({path, col: path.slice(0, path.lastIndexOf("/")), data}); if (error) throw new Error("Écriture Helper : " + error.message); };

/* ---------- EcoleDirecte ---------- */
let version = VERSIONS[0], token = "", cookies = "", gtk = "", jeton2fa = "";
async function appel(chemin, data = {}, extra = {}){
  const url = `${ED}${chemin}${chemin.includes("?") ? "&" : "?"}v=${version}`;
  const res = await fetch(url, {method: "POST", headers: {"Content-Type": "application/x-www-form-urlencoded", "User-Agent": UA,
    ...(token ? {"X-Token": token} : {}), ...(jeton2fa ? {"2fa-Token": jeton2fa} : {}), ...(gtk ? {"X-Gtk": gtk} : {}), ...(cookies ? {Cookie: cookies} : {}), ...extra},
    body: "data=" + encodeURIComponent(JSON.stringify(data))});
  const t = await res.text(); let j;
  try { j = JSON.parse(t); } catch { throw new Error(`EcoleDirecte a répondu autre chose que prévu (HTTP ${res.status}).`); }
  const tk = res.headers.get("x-token") || j.token; if (tk) token = tk;
  const f = res.headers.get("2fa-token"); if (f) jeton2fa = f;
  return j;
}
async function recupererGtk(){
  const res = await fetch(`${ED}/login.awp?gtk=1&v=${version}`, {headers: {"User-Agent": UA}});
  const liste = typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [res.headers.get("set-cookie") || ""];
  const paires = liste.flatMap(c => String(c).split(/,(?=\s*[^;,=\s]+=)/)).map(c => c.split(";")[0].trim()).filter(c => c.includes("="));
  cookies = paires.join("; ");
  const g = paires.find(p => p.startsWith("GTK=")); gtk = g ? g.slice(4) : "";
  if (!gtk) throw new Error("EcoleDirecte n'a pas donné de jeton de connexion (GTK).");
}
async function login(fa){
  await recupererGtk(); token = ""; jeton2fa = "";
  return appel("/login.awp", {identifiant: env("ED_IDENTIFIANT"), motdepasse: env("ED_MDP"), isReLogin: false, uuid: "", fa: fa ? [fa] : []});
}
// Connexion, avec la double authentification (QCM) si EcoleDirecte la demande
async function connecterED(etat){
  let r;
  for (const v of VERSIONS){ version = v; r = await login(etat.fa); if (r.code !== 517) break; }
  if (r.code === 200) return r;
  if (r.code === 505) throw new Error("Identifiant ou mot de passe EcoleDirecte incorrect (secrets ED_IDENTIFIANT / ED_MDP).");
  if (r.code !== 250) throw new Error(`Connexion EcoleDirecte refusée (code ${r.code}).`);
  // QCM : la bonne réponse est choisie par le gérant dans Helper (onglet Devoirs), puis réutilisée
  const q = await appel("/connexion/doubleauth.awp?verbe=get", {});
  if (q.code !== 200 || !q.data) throw new Error(`Impossible d'obtenir la question de sécurité (code ${q.code}).`);
  const question = b64(q.data.question), propositions = (q.data.propositions || []).map(b64);
  const reponse = (etat.reponses || {})[norm(question)];
  const choix = reponse ? propositions.find(p => norm(p) === norm(reponse)) : null;
  if (!choix){
    etat.qcm = {question, propositions, demandeLe: new Date().toISOString()};
    throw Object.assign(new Error("EcoleDirecte pose une question de sécurité : réponds-y dans Helper (onglet Devoirs), puis relance le robot."), {qcm: true});
  }
  const a = await appel("/connexion/doubleauth.awp?verbe=post", {choix: versB64(choix)});
  if (a.code !== 200 || !a.data || !a.data.cn) throw new Error(`La réponse à la question de sécurité a été refusée (code ${a.code}).`);
  etat.fa = {cn: a.data.cn, cv: a.data.cv}; delete etat.qcm;
  const r2 = await login(etat.fa);
  if (r2.code !== 200) throw new Error(`Connexion EcoleDirecte refusée après la question de sécurité (code ${r2.code}).`);
  return r2;
}
// HTML du cahier de texte → texte simple (retours à la ligne gardés, liens gardés en clair)
const ENT = {amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", rsquo: "’", lsquo: "‘", laquo: "«", raquo: "»", hellip: "…", eacute: "é", egrave: "è", agrave: "à", ccedil: "ç", ecirc: "ê", ocirc: "ô", ucirc: "û", icirc: "î", euml: "ë", iuml: "ï", oelig: "œ", deg: "°"};
function texte(html){
  return String(html || "")
    .replace(/<a\b[^>]*href="([^"]+)"[^>]*>(.*?)<\/a>/gis, (m, h, t) => { const s = t.replace(/<[^>]+>/g, "").trim(); return s && s !== h ? `${s} (${h})` : h; })
    .replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div|li|h\d)>/gi, "\n").replace(/<li[^>]*>/gi, "• ").replace(/<[^>]+>/g, "")
    .replace(/&#(\d+);/g, (m, n) => String.fromCodePoint(+n)).replace(/&#x([0-9a-f]+);/gi, (m, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&([a-z]+);/gi, (m, n) => ENT[n.toLowerCase()] ?? m)
    .replace(/[ \t ]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
}

async function principal(){
  await connecterHelper();
  const etat = (await lire("robot/ecoledirecte")) || {};
  const sauverEtat = (ok, message, extra = {}) => ecrire("robot/ecoledirecte", {...etat, dernier: {ok, message, le: new Date().toISOString(), ...extra}});
  try {
    const r = await connecterED(etat);
    const compte = (r.data && r.data.accounts || []).find(a => a.typeCompte === "E") || (r.data && r.data.accounts || [])[0];
    if (!compte) throw new Error("Aucun compte élève trouvé sur EcoleDirecte.");
    const eleve = compte.typeCompte === "E" ? compte.id : (((compte.profile || {}).eleves || [])[0] || {}).id;
    if (!eleve) throw new Error("Aucun élève trouvé sur ce compte EcoleDirecte.");
    const classe = compte.typeCompte === "E" ? ((compte.profile || {}).classe || {}).libelle || "" : "";
    const liste = await appel(`/Eleves/${eleve}/cahierdetexte.awp?verbe=get`, {});
    if (liste.code !== 200) throw new Error(`Lecture du cahier de texte impossible (code ${liste.code}).`);
    const dates = Object.keys(liste.data || {}).filter(d => /^\d{4}-\d{2}-\d{2}$/.test(d)).sort().slice(0, 30);
    const devoirs = [];
    for (const date of dates){
      await pause(400);
      const j = await appel(`/Eleves/${eleve}/cahierdetexte/${date}.awp?verbe=get`, {});
      if (j.code !== 200 || !j.data) continue;
      for (const m of j.data.matieres || []){
        const af = m.aFaire; if (!af) continue;
        devoirs.push({id: "ed-" + (af.idDevoir || m.id), date, matiere: m.matiere || "", code: m.codeMatiere || "", prof: String(m.nomProf || "").trim(), groupe: m.entityType === "G" ? m.entityLibelle || "" : "",
          interrogation: !!m.interrogation, donneLe: af.donneLe || "", contenu: texte(b64(af.contenu)).slice(0, 4000),
          documents: (af.documents || []).map(d => d.libelle).filter(Boolean).slice(0, 10)});
      }
    }
    await ecrire("edt/devoirs", {source: "ecoledirecte", classe, majLe: new Date().toISOString(), devoirs});
    await sauverEtat(true, `${devoirs.length} devoir(s) récupéré(s)`, {nb: devoirs.length});
    console.log(`OK : ${devoirs.length} devoir(s) sur ${dates.length} jour(s).`);
  } catch (e){
    await sauverEtat(false, e.message).catch(() => {});
    console.error("Échec :", e.message);
    process.exitCode = e.qcm ? 0 : 1;
  }
}
principal().catch(e => { console.error("Échec :", e.message); process.exitCode = 1; });
