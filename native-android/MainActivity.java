package com.prejmarseille.carnetdebord;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Enregistre le plugin SMS maison avant l'initialisation de Capacitor
        registerPlugin(SmsComposerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
