package com.vrp.herathwhatakpujaplayer; // Check your package name!

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private MediaPlayer mediaPlayer;
    private Button btnPlayPause, btnRewind, btnForward;
    private ImageButton btnSettings;
    private TextView txtTimer;
    private Handler handler = new Handler();

    // Constants for Persistence
    private static final String PREFS_NAME = "PujaPrefs";
    private static final String KEY_TIMESTAMP = "saved_timestamp";

    // Add to your variables at the top
    private static final String KEY_SHOW_FORWARD = "show_forward_button";
    private boolean isForwardVisible = false;

    // --- AUDIO FOCUS VARIABLES ---
    private android.media.AudioManager audioManager;
    private android.media.AudioFocusRequest focusRequest; // For Android 8.0+
    private android.media.AudioManager.OnAudioFocusChangeListener focusChangeListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- NEW: FORCE STATUS BAR TO BLACK ---
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(android.graphics.Color.BLACK);
        }
        // --------------------------------------

        // 1. KEEP SCREEN ON (Crucial)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        // 2. Initialize Views
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnSettings = findViewById(R.id.btnSettings);
        txtTimer = findViewById(R.id.txtTimer);

        setupAudioFocus();

        // 3. Setup MediaPlayer
        setupMediaPlayer();

        // 4. Button Listeners
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnRewind.setOnClickListener(v -> seekMedia(-20000)); // -20 seconds
        btnForward.setOnClickListener(v -> seekMedia(20000));  // +20 seconds
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // CHECK PREFERENCES: Should we show the button?
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isForwardVisible = prefs.getBoolean(KEY_SHOW_FORWARD, false); // Default is false (Hidden)

        updateForwardButtonVisibility();

        // 5. Update Timer Runnable
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mediaPlayer != null){
                    updateTimerText();
                }
                handler.postDelayed(this, 1000);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Show confirmation dialog instead of closing
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Exit Puja?")
                        .setMessage("Do you really want to stop the Puja and exit?")
                        .setPositiveButton("Yes, Exit", (dialog, which) -> {
                            finish(); // Actually close the app
                        })
                        .setNegativeButton("No", null) // Do nothing, stay in app
                        .show();
            }
        });
    }

    private void setupMediaPlayer() {
        // Load the MP3
        mediaPlayer = MediaPlayer.create(this, R.raw.puja_audio);

        // --- NEW: DETECT WHEN AUDIO FINISHES ---
        mediaPlayer.setOnCompletionListener(mp -> showPujaCompletionDialog());

        // --- SMART RESUME LOGIC ---
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedPos = prefs.getInt(KEY_TIMESTAMP, 0);

        if (savedPos > 0) {
            // Rewind 10 seconds for context
            int resumePos = Math.max(0, savedPos - 10000);
            mediaPlayer.seekTo(resumePos);
            Toast.makeText(this, "Ready to resume (Rewound 10s)", Toast.LENGTH_SHORT).show();
        }

        // Set UI to "Paused" state
        updatePlayButtonUI(false);
    }

    private void showPujaCompletionDialog() {
        // 1. Stop Audio & Release Focus
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        abandonAudioFocus();
        updatePlayButtonUI(false);

        // 2. Reset Timer to 00:00 (Visual fix)
        mediaPlayer.seekTo(0);
        updateTimerText();

        // 3. Reset the saved timestamp so next time it starts fresh
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putInt(KEY_TIMESTAMP, 0);
        editor.apply();

        // 4. Show the Completion Dialog
        // Ensure you have created 'dialog_puja_complete.xml' in res/layout!
        View view = getLayoutInflater().inflate(R.layout.dialog_puja_complete, null);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false) // User must click a button
                .create();

        Button btnExit = view.findViewById(R.id.btnExitApp);
        Button btnRestart = view.findViewById(R.id.btnRestart);

        btnExit.setOnClickListener(v -> {
            dialog.dismiss();
            finishAffinity(); // Close app
        });

        btnRestart.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(this, "Puja Reset", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void togglePlayPause() {
        if (mediaPlayer.isPlaying()) {
            // User wants to PAUSE
            mediaPlayer.pause();
            abandonAudioFocus(); // Let go of focus so other apps can play
            updatePlayButtonUI(false);
        } else {
            // User wants to PLAY
            if (requestAudioFocus()) { // Only play if the system says OK
                mediaPlayer.start();
                updatePlayButtonUI(true);
            } else {
                Toast.makeText(this, "Audio blocked (Call active?)", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void seekMedia(int delta) {
        if (mediaPlayer == null) return;
        int current = mediaPlayer.getCurrentPosition();
        int target = Math.max(0, Math.min(mediaPlayer.getDuration(), current + delta));
        mediaPlayer.seekTo(target);
    }

    private void updatePlayButtonUI(boolean isPlaying) {
        if (isPlaying) {
            btnPlayPause.setText("PAUSE");
            btnPlayPause.setBackgroundColor(getColor(android.R.color.holo_red_dark));
        } else {
            btnPlayPause.setText("PLAY");
            btnPlayPause.setBackgroundColor(getColor(android.R.color.holo_green_dark));
        }
    }

    // --- SETTINGS DIALOG (Reset & Jump) ---
    // --- UPDATED SETTINGS DIALOG ---
    private void showSettingsDialog() {
        // 1. Inflate the Custom Layout
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);

        // 2. Create the Dialog
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        // 3. Find Buttons
        Button btnReset = view.findViewById(R.id.btnReset);
        Button btnJump = view.findViewById(R.id.btnJump);
        Button btnToggle = view.findViewById(R.id.btnToggleForward);
        Button btnAppInfo = view.findViewById(R.id.btnAppInfo);
        Button btnDeveloper = view.findViewById(R.id.btnDeveloper);
        Button btnClose = view.findViewById(R.id.btnCloseSettings);

        // 4. Set Dynamic Text for Toggle Button
        String toggleText = isForwardVisible ? "Hide Forward Button" : "Show Forward Button";
        btnToggle.setText(toggleText);

        // 5. Set Click Listeners
        btnReset.setOnClickListener(v -> {
            mediaPlayer.seekTo(0);
            updateTimerText();
            updatePlayButtonUI(false);
            Toast.makeText(this, "Reset to Start", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnJump.setOnClickListener(v -> {
            dialog.dismiss();
            showJumpDialog();
        });

        btnToggle.setOnClickListener(v -> {
            toggleForwardButton();
            // Update text immediately without closing (optional)
            String newText = isForwardVisible ? "Hide Forward Button" : "Show Forward Button";
            btnToggle.setText(newText);
        });

        btnAppInfo.setOnClickListener(v -> {
            dialog.dismiss();
            showAppInfoDialog();
        });

        btnDeveloper.setOnClickListener(v -> {
            dialog.dismiss();
            showAboutDialog();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        // 6. Show it!
        dialog.show();
    }

    private void showAppInfoDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_info, null);

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    // --- NEW ABOUT DIALOG ---
    private void showAboutDialog() {
        // 1. Inflate the layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_about, null);

        // 2. Find the Link TextViews
        TextView txtPortfolio = dialogView.findViewById(R.id.linkPortfolio);
        TextView txtLinkedIn = dialogView.findViewById(R.id.linkLinkedIn);

        // 3. Set Click Listeners to open URLs
        txtPortfolio.setOnClickListener(v -> openUrl("https://vivekpandita.github.io/")); // Replace with your actual URL
        txtLinkedIn.setOnClickListener(v -> openUrl("http://linkedin.com/in/vivek-pandita")); // Replace with your actual URL

        // 4. Show Dialog
        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    // Helper method to open browser
    private void openUrl(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    private void showJumpDialog() {
        final EditText input = new EditText(this);
        input.setHint("Enter minute (e.g. 45)");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(this)
                .setTitle("Jump to Time")
                .setMessage("Enter the minute to jump to:")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    String val = input.getText().toString();
                    if (!val.isEmpty()) {
                        int minutes = Integer.parseInt(val);
                        int millis = minutes * 60 * 1000;
                        if (millis < mediaPlayer.getDuration()) {
                            mediaPlayer.seekTo(millis);
                        } else {
                            Toast.makeText(this, "Time exceeds audio length", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateTimerText() {
        if(mediaPlayer == null) return;
        int current = mediaPlayer.getCurrentPosition();
        int total = mediaPlayer.getDuration();

        String currStr = String.format("%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(current),
                TimeUnit.MILLISECONDS.toSeconds(current) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(current))
        );
        String totalStr = String.format("%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(total),
                TimeUnit.MILLISECONDS.toSeconds(total) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(total))
        );

        txtTimer.setText(currStr + " / " + totalStr);
    }

    // Save position when app closes or pauses
    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null) {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putInt(KEY_TIMESTAMP, mediaPlayer.getCurrentPosition());
            editor.apply();

            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause(); // Standard practice to pause on exit
            }
        }
        abandonAudioFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        abandonAudioFocus();
    }

    private void toggleForwardButton() {
        isForwardVisible = !isForwardVisible; // Flip the boolean

        // Save to preferences immediately
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_SHOW_FORWARD, isForwardVisible);
        editor.apply();

        updateForwardButtonVisibility();

        String msg = isForwardVisible ? "Forward Button Enabled" : "Forward Button Disabled";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void updateForwardButtonVisibility() {
        if (isForwardVisible) {
            btnForward.setVisibility(View.VISIBLE);
        } else {
            btnForward.setVisibility(View.GONE);
        }
        // The Play button will automatically expand/contract because of layout_weight="1"
    }

    // --- AUDIO FOCUS HELPERS ---

    private void setupAudioFocus() {
        audioManager = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Define what happens when focus changes (e.g., phone rings)
        focusChangeListener = focusChange -> {
            switch (focusChange) {
                case android.media.AudioManager.AUDIOFOCUS_LOSS:
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Call received -> PAUSE IMMEDIATELY
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        updatePlayButtonUI(false);
                    }
                    break;
                case android.media.AudioManager.AUDIOFOCUS_GAIN:
                    // Call ended. We do NOT auto-resume. Dad controls the flow.
                    break;
            }
        };
    }

    private boolean requestAudioFocus() {
        int result;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Newer Android Versions (8.0+)
            android.media.AudioAttributes playbackAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            focusRequest = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();

            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            // Older Android Versions
            result = audioManager.requestAudioFocus(focusChangeListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }
}