package com.prejmarseille.carnetdebord;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Plugin natif maison : ouvre UNIQUEMENT l'application SMS/MMS native du telephone
 * (jamais le menu de partage), avec le destinataire (adresse email -> MMS),
 * le texte et les pieces jointes deja remplis.
 */
@CapacitorPlugin(name = "SmsComposer")
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
