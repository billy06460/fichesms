package com.prejmarseille.carnetdebord;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
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

/**
 * Plugin natif maison : ouvre l'application SMS/MMS par defaut du telephone
 * avec le destinataire (numero ou adresse email, envoye en MMS), le texte et les pieces jointes (PDF + photos) deja remplis.
 * L'utilisateur n'a plus qu'a appuyer sur "Envoyer" dans l'appli Messages.
 */
@CapacitorPlugin(name = "SmsComposer")
public class SmsComposerPlugin extends Plugin {

    @PluginMethod
    public void composeSms(PluginCall call) {
        final String to = call.getString("to", "");
        final String body = call.getString("body", "");
        JSArray attachments = call.getArray("attachments", new JSArray());

        final ArrayList<Uri> uris = new ArrayList<>();
        boolean allImages = true;

        try {
            // Les pieces jointes sont ecrites dans le cache de l'app puis partagees
            // via le FileProvider deja declare par Capacitor.
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
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                    allImages = false;
                }
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

        final Intent intent;
        if (uris.isEmpty()) {
            intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to, "@+")));
            intent.putExtra("sms_body", body);
        } else {
            intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            intent.setType(allImages ? "image/*" : "*/*");
            if (uris.size() == 1) {
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Extras reconnus par Google Messages, Samsung Messages, etc.
            intent.putExtra("address", to);
            intent.putExtra("sms_body", body);
            intent.putExtra(Intent.EXTRA_TEXT, body);
        }

        getActivity().runOnUiThread(() -> {
            JSObject ret = new JSObject();
            String smsPackage = Telephony.Sms.getDefaultSmsPackage(getContext());
            try {
                // 1) Appli SMS par defaut, directement (destinataire pre-rempli)
                if (smsPackage != null) intent.setPackage(smsPackage);
                getActivity().startActivity(intent);
                ret.put("mode", "direct");
                call.resolve(ret);
            } catch (ActivityNotFoundException e1) {
                try {
                    // 2) Repli : menu de partage Android (le numero devra etre choisi a la main)
                    intent.setPackage(null);
                    Intent chooser = Intent.createChooser(intent, "Envoyer la fiche");
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    getActivity().startActivity(chooser);
                    ret.put("mode", "chooser");
                    call.resolve(ret);
                } catch (Exception e2) {
                    call.reject("Aucune application SMS disponible : " + e2.getMessage(), e2);
                }
            } catch (Exception e) {
                call.reject("Ouverture de l'application SMS impossible : " + e.getMessage(), e);
            }
        });
    }
}
