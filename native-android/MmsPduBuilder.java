package com.prejmarseille.carnetdebord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Construit un MMS "m-send-req" (norme OMA MMS 1.2 / WAP-209) pret a etre
 * remis au systeme Android par SmsManager.sendMultimediaMessage().
 *
 * Structure produite :
 *   en-tetes MMS (type, transaction, version, date, expediteur, destinataire(s), objet)
 *   Content-Type: multipart/mixed
 *     - partie 1 : text/plain; charset=utf-8  (le texte de la fiche)
 *     - parties suivantes : les fichiers joints (ex. application/pdf)
 *
 * multipart/mixed (et non multipart/related + SMIL) : les passerelles MMS -> email
 * des operateurs transforment alors chaque fichier en vraie piece jointe.
 */
public class MmsPduBuilder {

    public static class Part {
        final String contentType;   // ex. "application/pdf"
        final String fileName;      // ex. "fiche.pdf" (ASCII)
        final byte[] data;

        public Part(String contentType, String fileName, byte[] data) {
            this.contentType = contentType;
            this.fileName = fileName;
            this.data = data;
        }
    }

    // Codes des en-tetes MMS (valeur | 0x80)
    private static final int H_CONTENT_TYPE   = 0x84;
    private static final int H_DATE           = 0x85;
    private static final int H_DELIVERY_REP   = 0x86;
    private static final int H_FROM           = 0x89;
    private static final int H_MESSAGE_CLASS  = 0x8A;
    private static final int H_MESSAGE_TYPE   = 0x8C;
    private static final int H_MMS_VERSION    = 0x8D;
    private static final int H_READ_REPORT    = 0x90;
    private static final int H_SUBJECT        = 0x96;
    private static final int H_TO             = 0x97;
    private static final int H_TRANSACTION_ID = 0x98;

    // En-tetes des parties (WSP)
    private static final int P_CONTENT_LOCATION = 0x8E;
    private static final int P_CONTENT_ID       = 0xC0;

    private static final int MESSAGE_TYPE_SEND_REQ = 0x80;
    private static final int MMS_VERSION_1_2       = 0x92;
    private static final int VALUE_NO              = 0x81;
    private static final int CLASS_PERSONAL        = 0x80;
    private static final int INSERT_ADDRESS_TOKEN  = 0x81;
    private static final int CT_MULTIPART_MIXED    = 0xA3;  // application/vnd.wap.multipart.mixed
    private static final int CT_TEXT_PLAIN         = 0x83;  // text/plain
    private static final int PARAM_CHARSET         = 0x81;
    private static final int PARAM_NAME            = 0x85;
    private static final int CHARSET_UTF8          = 0xEA;  // 106 | 0x80

    public static byte[] build(List<String> recipients, String subject, String text, List<Part> files) {
        Buf h = new Buf();

        // Les 3 premiers en-tetes doivent etre dans cet ordre
        h.b(H_MESSAGE_TYPE); h.b(MESSAGE_TYPE_SEND_REQ);
        h.b(H_TRANSACTION_ID); h.text("T" + Long.toHexString(System.currentTimeMillis()));
        h.b(H_MMS_VERSION); h.b(MMS_VERSION_1_2);

        // Date (secondes depuis 1970, entier long)
        h.b(H_DATE); h.longInteger(System.currentTimeMillis() / 1000L);

        // Expediteur : insere par le reseau (le numero du telephone)
        h.b(H_FROM); h.b(0x01); h.b(INSERT_ADDRESS_TOKEN);

        // Destinataires : une adresse email s'ecrit telle quelle
        for (String r : recipients) {
            String addr = r.trim();
            if (addr.isEmpty()) continue;
            h.b(H_TO); h.text(addr);
        }

        if (subject != null && !subject.isEmpty()) {
            h.b(H_SUBJECT); h.encodedStringUtf8(subject);
        }

        h.b(H_MESSAGE_CLASS); h.b(CLASS_PERSONAL);
        h.b(H_DELIVERY_REP); h.b(VALUE_NO);
        h.b(H_READ_REPORT); h.b(VALUE_NO);

        // Content-Type en DERNIER, suivi du corps multipart
        h.b(H_CONTENT_TYPE); h.b(CT_MULTIPART_MIXED);

        List<byte[]> entries = new ArrayList<>();

        // Partie texte
        if (text != null && !text.isEmpty()) {
            Buf ct = new Buf();
            ct.b(CT_TEXT_PLAIN); ct.b(PARAM_CHARSET); ct.b(CHARSET_UTF8);
            Buf hdr = new Buf();
            hdr.valueLength(ct.size()); hdr.bytes(ct.toBytes());
            hdr.b(P_CONTENT_LOCATION); hdr.text("texte.txt");
            hdr.b(P_CONTENT_ID); hdr.quotedText("<texte>");
            entries.add(entry(hdr.toBytes(), text.getBytes(StandardCharsets.UTF_8)));
        }

        // Fichiers joints
        int i = 0;
        for (Part p : files) {
            i++;
            Buf ct = new Buf();
            ct.text(p.contentType);
            ct.b(PARAM_NAME); ct.text(p.fileName);
            Buf hdr = new Buf();
            hdr.valueLength(ct.size()); hdr.bytes(ct.toBytes());
            hdr.b(P_CONTENT_LOCATION); hdr.text(p.fileName);
            hdr.b(P_CONTENT_ID); hdr.quotedText("<piece" + i + ">");
            entries.add(entry(hdr.toBytes(), p.data));
        }

        h.uintvar(entries.size());
        for (byte[] e : entries) h.bytes(e);
        return h.toBytes();
    }

    private static byte[] entry(byte[] headers, byte[] data) {
        Buf e = new Buf();
        e.uintvar(headers.length);
        e.uintvar(data.length);
        e.bytes(headers);
        e.bytes(data);
        return e.toBytes();
    }

    /** Petit tampon avec les encodages WSP necessaires. */
    private static class Buf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void b(int v) { out.write(v & 0xFF); }

        void bytes(byte[] a) { out.write(a, 0, a.length); }

        int size() { return out.size(); }

        byte[] toBytes() { return out.toByteArray(); }

        /** Text-string : octets + 0x00 (avec 0x7F devant si le 1er octet >= 0x80) */
        void text(String s) {
            byte[] a = s.getBytes(StandardCharsets.UTF_8);
            if (a.length > 0 && (a[0] & 0xFF) >= 0x80) b(0x7F);
            bytes(a);
            b(0x00);
        }

        /** Quoted-string : 0x22 + texte + 0x00 */
        void quotedText(String s) {
            b(0x22);
            bytes(s.getBytes(StandardCharsets.UTF_8));
            b(0x00);
        }

        /** Encoded-string-value avec jeu de caracteres UTF-8 */
        void encodedStringUtf8(String s) {
            Buf tmp = new Buf();
            tmp.b(CHARSET_UTF8);
            tmp.text(s);
            valueLength(tmp.size());
            bytes(tmp.toBytes());
        }

        /** Value-length : 1 octet si < 31, sinon 31 + uintvar */
        void valueLength(int len) {
            if (len < 31) {
                b(len);
            } else {
                b(31);
                uintvar(len);
            }
        }

        /** Entier variable : groupes de 7 bits, bit de poids fort = "suite" */
        void uintvar(long v) {
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int k = n - 1; k >= 0; k--) {
                int octet = tmp[k] & 0x7F;
                if (k != 0) octet |= 0x80;
                b(octet);
            }
        }

        /** Long-integer : longueur courte (nb d'octets) + valeur big-endian */
        void longInteger(long v) {
            byte[] tmp = new byte[8];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0xFF);
                v >>>= 8;
            } while (v != 0);
            b(n);
            for (int k = n - 1; k >= 0; k--) b(tmp[k]);
        }
    }
}
