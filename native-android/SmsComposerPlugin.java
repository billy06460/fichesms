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

    private void sendNext(final PluginCall call, final SmsManager sm, final List<String> recipients, final int index,
                          final String subject, final String body, final List<MmsPduBuilder.Part> parts,
                          final int maxSize, final JSArray results) {
        if (index >= recipients.size()) {
            JSObject ret = new JSObject();
            ret.put("results", results);
            ret.put("maxSize", maxSize);
            call.resolve(ret);
            return;
        }
        final String to = recipients.get(index);
        final Context ctx = getContext();

        final byte[] pdu;
        final Uri pduUri;
        try {
            List<String> one = new ArrayList<>();
            one.add(to);
            pdu = MmsPduBuilder.build(one, subject, body, parts);
            if (maxSize > 0 && pdu.length > maxSize) {
                results.put(result(to, false, -2,
                    "Message trop lourd pour l'opérateur (" + (pdu.length / 1024) + " Ko, maximum " + (maxSize / 1024) + " Ko)."));
                sendNext(call, sm, recipients, index + 1, subject, body, parts, maxSize, results);
                return;
            }
            File dir = new File(ctx.getCacheDir(), "mms_out");
            dir.mkdirs();
            File f = new File(dir, "send_" + System.currentTimeMillis() + "_" + index + ".pdu");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(pdu);
            }
            pduUri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        } catch (Exception e) {
            results.put(result(to, false, -3, "Construction du MMS impossible : " + e.getMessage()));
            sendNext(call, sm, recipients, index + 1, subject, body, parts, maxSize, results);
            return;
        }

        final String action = ctx.getPackageName() + ACTION_MMS_SENT + "." + System.currentTimeMillis() + "." + index;
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = { false };

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (done[0]) return;
                done[0] = true;
                handler.removeCallbacksAndMessages(null);
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                int code = getResultCode();
                if (code == Activity.RESULT_OK) {
                    results.put(result(to, true, 0, null));
                } else {
                    results.put(result(to, false, code, mmsErrorText(code)));
                }
                sendNext(call, sm, recipients, index + 1, subject, body, parts, maxSize, results);
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
        PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) (System.currentTimeMillis() & 0x7FFFFFF), sentIntent, flags);

        // Sécurité : si le système ne répond pas, on n'attend pas indéfiniment
        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            results.put(result(to, false, -4, "Pas de réponse du réseau après 3 minutes (données mobiles activées ?)."));
            sendNext(call, sm, recipients, index + 1, subject, body, parts, maxSize, results);
        }, MMS_TIMEOUT_MS);

        try {
            sm.sendMultimediaMessage(ctx, pduUri, null, null, pi);
        } catch (Exception e) {
            if (done[0]) return;
            done[0] = true;
            handler.removeCallbacksAndMessages(null);
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            results.put(result(to, false, -5, "Envoi refusé par le système : " + e.getMessage()));
            sendNext(call, sm, recipients, index + 1, subject, body, parts, maxSize, results);
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
