package com.prejmarseille.carnetdebord;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Plugin natif maison : ouvre UNIQUEMENT l'application SMS/MMS native du telephone
 * (jamais le menu de partage), avec le destinataire (adresse email -> MMS),
 * le texte et les pieces jointes deja remplis.
 */
@CapacitorPlugin(
    name = "SmsComposer",
    permissions = {
        @Permission(strings = { Manifest.permission.SEND_SMS }, alias = "sms")
    }
)
public class SmsComposerPlugin extends Plugin {

    private static final String GOOGLE_MESSAGES = "com.google.android.apps.messaging";

    // Applis SMS natives connues, essayees apres l'appli SMS par defaut
    private static final String[] KNOWN_SMS_APPS = {
        "com.google.android.apps.messaging",   // Google Messages
        "com.samsung.android.messaging",       // Samsung Messages
        "com.android.mms",                     // AOSP / Xiaomi / autres
        "com.android.messaging"
    };

    @PluginMethod
    public void composeSms(PluginCall call) {
        final String to = call.getString("to", "").trim();
        final String body = call.getString("body", "");
        JSArray attachments = call.getArray("attachments", new JSArray());

        final ArrayList<Uri> uris = new ArrayList<>();
        try {
            File dir = new File(getContext().getCacheDir(), "sms_attachments");
            if (dir.exists()) {
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
            }
            dir.mkdirs();
            String authority = getContext().getPackageName() + ".fileprovider";
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject a = attachments.getJSONObject(i);
                String name = a.optString("name", "piece_" + (i + 1));
                name = name.replaceAll("[\\\\/:*?\"<>|]+", "-");
                byte[] bytes = Base64.decode(a.getString("data"), Base64.DEFAULT);
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
                uris.add(FileProvider.getUriForFile(getContext(), authority, f));
            }
        } catch (Exception e) {
            call.reject("Preparation des pieces jointes impossible : " + e.getMessage(), e);
            return;
        }

        // Filet de securite : l'adresse est copiee dans le presse-papiers, au cas
        // ou l'appli Messages ignorerait le destinataire transmis.
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && !to.isEmpty()) cm.setPrimaryClip(ClipData.newPlainText("Destinataire", to.replace(",", ", ")));
        } catch (Exception ignored) { }

        getActivity().runOnUiThread(() -> {
            LinkedHashSet<String> packages = new LinkedHashSet<>();
            String def = Telephony.Sms.getDefaultSmsPackage(getContext());
            if (def != null) packages.add(def);
            for (String p : KNOWN_SMS_APPS) packages.add(p);

            String lastError = "";
            for (String pkg : packages) {
                try {
                    Intent intent = (GOOGLE_MESSAGES.equals(pkg) && uris.size() <= 1)
                        ? buildSendToIntent(to, body, uris, pkg)
                        : buildIntent(to, body, uris, pkg);
                    getActivity().startActivity(intent);
                    JSObject ret = new JSObject();
                    ret.put("package", pkg);
                    call.resolve(ret);
                    return;
                } catch (ActivityNotFoundException e) {
                    lastError = e.getMessage();
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }
            call.reject("Aucune application SMS native n'a accepte le message : " + lastError);
        });
    }

    // =====================================================================
    //  ENVOI AUTOMATIQUE DE MMS (sans passer par l'appli Messages)
    // =====================================================================

    private static final String ACTION_MMS_SENT = ".MMS_SENT";
    private static final long MMS_TIMEOUT_MS = 180_000L;

    /**
     * sendMms({ to: ["a@b.fr", "c@d.fr"], subject, body, attachments: [{name, data(base64)}] })
     * Envoie UN MMS par destinataire, l'un apres l'autre, directement via le systeme.
     * Resout avec { results: [{ to, ok, code, error }] }.
     */
    @PluginMethod
    public void sendMms(PluginCall call) {
        if (getPermissionState("sms") != PermissionState.GRANTED) {
            requestPermissionForAlias("sms", call, "smsPermissionCallback");
            return;
        }
        doSendMms(call);
    }

    @PermissionCallback
    private void smsPermissionCallback(PluginCall call) {
        if (getPermissionState("sms") == PermissionState.GRANTED) {
            doSendMms(call);
        } else {
            call.reject("Autorisation « Envoyer des SMS » refusée. Activez-la dans Paramètres > Applications > Carnet de bord > Autorisations.", "PERMISSION_DENIED");
        }
    }

    private void doSendMms(final PluginCall call) {
        final List<String> recipients = new ArrayList<>();
        final String subject = call.getString("subject", "");
        final String body = call.getString("body", "");
        final List<MmsPduBuilder.Part> parts = new ArrayList<>();
        try {
            JSArray to = call.getArray("to", new JSArray());
            for (int i = 0; i < to.length(); i++) {
                String r = to.getString(i).trim();
                if (!r.isEmpty()) recipients.add(r);
            }
            JSArray attachments = call.getArray("attachments", new JSArray());
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject a = attachments.getJSONObject(i);
                String name = asciiFileName(a.optString("name", "piece_" + (i + 1) + ".pdf"));
                String type = name.toLowerCase().endsWith(".pdf") ? "application/pdf"
                    : (name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
                parts.add(new MmsPduBuilder.Part(type, name, Base64.decode(a.getString("data"), Base64.DEFAULT)));
            }
        } catch (Exception e) {
            call.reject("Données du message invalides : " + e.getMessage(), e);
            return;
        }
        if (recipients.isEmpty()) {
            call.reject("Aucun destinataire.");
            return;
        }

        final SmsManager sm;
        try {
            sm = getSmsManager();
        } catch (Exception e) {
            call.reject("Service MMS indisponible sur ce téléphone : " + e.getMessage(), e);
            return;
        }

        // Taille maximale autorisée par l'opérateur (0 = inconnue)
        int maxSize = 0;
        try {
            Bundle cfg = sm.getCarrierConfigValues();
            if (cfg != null) maxSize = cfg.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0);
        } catch (Exception ignored) { }

        final JSArray results = new JSArray();
        final int finalMaxSize = maxSize;
        sendNext(call, sm, recipients, 0, subject, body, parts, finalMaxSize, results);
    }

    // Pause entre deux MMS : laisse au telephone le temps de refermer la
    // connexion MMS du premier envoi avant d'ouvrir celle du suivant.
    private static final long DELAY_BETWEEN_MMS_MS = 5_000L;
    // Nouvel essai automatique en cas d'echec (erreur reseau temporaire...)
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 10_000L;

    /** Tout ce qui est commun aux envois d'un meme appel */
    private static class MmsJob {
        PluginCall call;
        SmsManager sm;
        List<String> recipients;
        String subject;
        String body;
        List<MmsPduBuilder.Part> parts;
        int maxSize;
        JSArray results = new JSArray();
        final Handler handler = new Handler(Looper.getMainLooper());
        int lastSize = 0;
    }

    private void sendNext(final PluginCall call, final SmsManager sm, final List<String> recipients, final int index,
                          final String subject, final String body, final List<MmsPduBuilder.Part> parts,
                          final int maxSize, final JSArray results) {
        MmsJob job = new MmsJob();
        job.call = call; job.sm = sm; job.recipients = recipients; job.subject = subject;
        job.body = body; job.parts = parts; job.maxSize = maxSize; job.results = results;
        sendOne(job, index, 1);
    }

    private void finishRecipient(final MmsJob job, final int index, JSObject res) {
        job.results.put(res);
        final int next = index + 1;
        if (next >= job.recipients.size()) {
            JSObject ret = new JSObject();
            ret.put("results", job.results);
            ret.put("maxSize", job.maxSize);
            job.call.resolve(ret);
        } else {
            job.handler.postDelayed(() -> sendOne(job, next, 1), DELAY_BETWEEN_MMS_MS);
        }
    }

    private void failOrRetry(final MmsJob job, final int index, final int attempt, int code, String error, boolean retryable) {
        if (retryable && attempt < MAX_ATTEMPTS) {
            job.handler.postDelayed(() -> sendOne(job, index, attempt + 1), RETRY_DELAY_MS);
        } else {
            JSObject r = result(job.recipients.get(index), false, code,
                error + (attempt > 1 ? " (" + attempt + " essais)" : ""));
            finishRecipient(job, index, r);
        }
    }

    private void sendOne(final MmsJob job, final int index, final int attempt) {
        final String to = job.recipients.get(index);
        final Context ctx = getContext();

        final Uri pduUri;
        try {
            List<String> one = new ArrayList<>();
            one.add(to);
            byte[] pdu = MmsPduBuilder.build(one, job.subject, job.body, job.parts);
            job.lastSize = pdu.length;
            if (job.maxSize > 0 && pdu.length > job.maxSize) {
                failOrRetry(job, index, attempt, -2,
                    "Message trop lourd pour l'opérateur (" + (pdu.length / 1024) + " Ko, maximum " + (job.maxSize / 1024) + " Ko).", false);
                return;
            }
            File dir = new File(ctx.getCacheDir(), "mms_out");
            dir.mkdirs();
            File f = new File(dir, "send_" + System.currentTimeMillis() + "_" + index + "_" + attempt + ".pdu");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(pdu);
            }
            pduUri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        } catch (Exception e) {
            failOrRetry(job, index, attempt, -3, "Construction du MMS impossible : " + e.getMessage(), false);
            return;
        }

        // Action et code de requete uniques pour chaque envoi (evite toute confusion
        // entre la confirmation du 1er MMS et celle du 2e)
        final long stamp = System.currentTimeMillis();
        final String action = ctx.getPackageName() + ACTION_MMS_SENT + "." + stamp + "." + index + "." + attempt;
        final boolean[] done = { false };
        final Runnable[] timeout = new Runnable[1];

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (done[0]) return;
                done[0] = true;
                job.handler.removeCallbacks(timeout[0]);
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                int code = getResultCode();
                // Reponse du serveur MMS de l'operateur (m-send-conf) et statut HTTP
                byte[] conf = null;
                int http = 0;
                try {
                    conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA);
                    http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0);
                } catch (Exception ignored) { }
                int[] st = parseSendConfStatus(conf);   // {statut, trouve}
                String opText = parseSendConfText(conf);
                String detail = " [code " + code
                    + (http != 0 ? ", HTTP " + http : "")
                    + (st[1] == 1 ? ", statut 0x" + Integer.toHexString(st[0]).toUpperCase() : "")
                    + (opText != null ? ", « " + opText + " »" : "")
                    + ", " + (job.lastSize / 1024) + " Ko]";

                if (code == Activity.RESULT_OK && (st[1] == 0 || st[0] == 0x80)) {
                    JSObject r = result(to, true, 0, null);
                    r.put("attempts", attempt);
                    r.put("detail", detail);
                    finishRecipient(job, index, r);
                } else if (code == Activity.RESULT_OK) {
                    // Le telephone a bien envoye, mais l'operateur a refuse le message
                    boolean transient_ = (st[0] & 0xE0) == 0xC0;
                    failOrRetry(job, index, attempt, st[0], responseStatusText(st[0]) + detail, transient_);
                } else {
                    failOrRetry(job, index, attempt, code, mmsErrorText(code) + detail, true);
                }
            }
        };

        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }

        Intent sentIntent = new Intent(action).setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        int requestCode = (int) ((stamp + index * 7919L + attempt * 104729L) & 0x7FFFFFF);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode, sentIntent, flags);

        // Securite : si le systeme ne repond pas, on n'attend pas indefiniment
        timeout[0] = () -> {
            if (done[0]) return;
            done[0] = true;
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            failOrRetry(job, index, attempt, -4, "Pas de réponse du réseau après 3 minutes (données mobiles activées ?).", true);
        };
        job.handler.postDelayed(timeout[0], MMS_TIMEOUT_MS);

        try {
            job.sm.sendMultimediaMessage(ctx, pduUri, null, null, pi);
        } catch (Exception e) {
            if (done[0]) return;
            done[0] = true;
            job.handler.removeCallbacks(timeout[0]);
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            failOrRetry(job, index, attempt, -5, "Envoi refusé par le système : " + e.getMessage(), true);
        }
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        int subId = -1;
        if (Build.VERSION.SDK_INT >= 24) subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager sm = getContext().getSystemService(SmsManager.class);
            return (subId != -1) ? sm.createForSubscriptionId(subId) : sm;
        }
        return (subId != -1) ? SmsManager.getSmsManagerForSubscriptionId(subId) : SmsManager.getDefault();
    }

    private static JSObject result(String to, boolean ok, int code, String error) {
        JSObject o = new JSObject();
        o.put("to", to);
        o.put("ok", ok);
        o.put("code", code);
        if (error != null) o.put("error", error);
        return o;
    }

    /** Lit X-Mms-Response-Status (0x92) dans la reponse m-send-conf. Retourne {statut, trouve}. */
    private static int[] parseSendConfStatus(byte[] d) {
        int[] res = { 0, 0 };
        if (d == null) return res;
        int i = 0;
        try {
            while (i < d.length) {
                int h = d[i++] & 0xFF;
                if (h == 0x92) { res[0] = d[i] & 0xFF; res[1] = 1; return res; }
                i = skipHeaderValue(d, i, h);
                if (i < 0) break;
            }
        } catch (Exception ignored) { }
        return res;
    }

    /** Lit X-Mms-Response-Text (0x93) : explication en clair donnee par l'operateur. */
    private static String parseSendConfText(byte[] d) {
        if (d == null) return null;
        int i = 0;
        try {
            while (i < d.length) {
                int h = d[i++] & 0xFF;
                if (h == 0x93) {
                    int b = d[i] & 0xFF;
                    int start = i, end;
                    if (b < 31) {            // value-length + charset + texte
                        int len = b; start = i + 1; end = start + len;
                        start++;             // saute le jeu de caracteres
                    } else if (b == 31) {
                        return null;
                    } else {
                        end = i; while (end < d.length && d[end] != 0) end++;
                    }
                    if (start < d.length && (d[start] & 0xFF) == 0x7F) start++;
                    int stop = start; while (stop < end && stop < d.length && d[stop] != 0) stop++;
                    String t = new String(d, start, Math.max(0, stop - start), java.nio.charset.StandardCharsets.UTF_8).trim();
                    return t.isEmpty() ? null : t;
                }
                i = skipHeaderValue(d, i, h);
                if (i < 0) break;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Avance apres la valeur d'un en-tete connu de m-send-conf ; -1 si inconnu. */
    private static int skipHeaderValue(byte[] d, int i, int h) {
        switch (h) {
            case 0x8C: case 0x8D: case 0x92: return i + 1;          // valeurs sur 1 octet
            case 0x98: case 0x8B: {                                 // text-string
                while (i < d.length && d[i] != 0) i++;
                return i + 1;
            }
            case 0x93: {                                            // encoded-string-value
                int b = d[i] & 0xFF;
                if (b < 31) return i + 1 + b;
                while (i < d.length && d[i] != 0) i++;
                return i + 1;
            }
            default: return -1;
        }
    }

    private static String responseStatusText(int s) {
        switch (s) {
            case 0xC0: return "Refus temporaire de l'opérateur.";
            case 0xC1: case 0xE3: case 0x84: return "L'opérateur ne reconnaît pas l'adresse du destinataire.";
            case 0xC3: case 0x86: return "Problème réseau chez l'opérateur.";
            case 0xE1: case 0x82: return "Service refusé par l'opérateur (option MMS vers email non autorisée ?).";
            case 0xE2: case 0x83: return "L'opérateur juge le format du message incorrect.";
            case 0xE5: case 0x87: return "Contenu refusé par l'opérateur (taille ou type de pièce jointe).";
            case 0xEB: return "Crédit insuffisant pour l'envoi de MMS.";
            default:   return "Message refusé par l'opérateur.";
        }
    }

    private static String mmsErrorText(int code) {
        switch (code) {
            case SmsManager.MMS_ERROR_INVALID_APN: return "Réglages MMS (APN) de l'opérateur invalides.";
            case SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS: return "Connexion au serveur MMS de l'opérateur impossible.";
            case SmsManager.MMS_ERROR_HTTP_FAILURE: return "Le serveur MMS de l'opérateur a refusé le message.";
            case SmsManager.MMS_ERROR_IO_ERROR: return "Erreur de lecture du message.";
            case SmsManager.MMS_ERROR_RETRY: return "Erreur temporaire, réessayez.";
            case SmsManager.MMS_ERROR_CONFIGURATION_ERROR: return "Configuration MMS de l'opérateur introuvable.";
            case SmsManager.MMS_ERROR_NO_DATA_NETWORK: return "Pas de données mobiles : activez-les puis réessayez.";
            default: return "Échec de l'envoi du MMS (code " + code + ").";
        }
    }

    private static String asciiFileName(String name) {
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replaceAll("[\\p{M}]", "")
            .replaceAll("[^A-Za-z0-9._-]+", "_");
        return n.isEmpty() ? "piece.pdf" : n;
    }

    /**
     * Google Messages ignore le destinataire transmis dans un partage classique
     * (ACTION_SEND). Il le respecte en revanche quand il est place dans l'adresse
     * de l'intent ACTION_SENDTO "mmsto:", forme documentee par Android pour un
     * SMS/MMS avec piece jointe (une seule piece jointe dans ce cas).
     */
    private Intent buildSendToIntent(String to, String body, ArrayList<Uri> uris, String pkg) {
        Intent intent = new Intent(Intent.ACTION_SENDTO,
            Uri.parse((uris.isEmpty() ? "smsto:" : "mmsto:") + Uri.encode(to, "@+.,")));
        intent.putExtra("sms_body", body);
        intent.putExtra(Intent.EXTRA_TEXT, body);
        if (!uris.isEmpty()) {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            intent.setClipData(ClipData.newRawUri("", uris.get(0)));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        intent.setPackage(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Intent buildIntent(String to, String body, ArrayList<Uri> uris, String pkg) {
        Intent intent;
        if (uris.isEmpty()) {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to, "@+.,")));
        } else {
            intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            intent.setType("application/pdf");
            if (uris.size() == 1) {
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        // Toutes les variantes connues pour transmettre le destinataire
        intent.putExtra("address", to);
        String[] list = to.split(",");
        intent.putExtra("addresses", list);
        intent.putExtra(Intent.EXTRA_EMAIL, list);
        intent.putExtra(Intent.EXTRA_PHONE_NUMBER, to);
        intent.putExtra("sms_body", body);
        intent.putExtra(Intent.EXTRA_TEXT, body);
        intent.setPackage(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
