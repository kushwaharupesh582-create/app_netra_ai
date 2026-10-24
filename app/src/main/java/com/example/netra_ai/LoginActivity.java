package com.example.netra_ai;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etEmail, etPassword;
    private RadioGroup rgRole;
    private RadioButton rbUser;
    private Button btnLogin;
    private Button btnSkip;
    private ProgressBar progressBar;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // Commented out the check so that Login Screen always opens
        // if (mAuth.getCurrentUser() != null) {
        //    goToMainActivity();
        //    return;
        // }

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        rgRole = findViewById(R.id.rgRole);
        rbUser = findViewById(R.id.rbUser);
        btnLogin = findViewById(R.id.btnLogin);
        btnSkip = findViewById(R.id.btnSkip);
        progressBar = findViewById(R.id.progressBar);

        btnLogin.setOnClickListener(v -> loginOrRegister());
        btnSkip.setOnClickListener(v -> {
            String role = rbUser.isChecked() ? "User" : "Guardian";
            routeUser(role);
        });
    }

    private void loginOrRegister() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String role = rbUser.isChecked() ? "User" : "Guardian";

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        btnLogin.setEnabled(false);

        // Log out any previously logged-in user so they can select a new role/account
        if (mAuth.getCurrentUser() != null) {
            mAuth.signOut();
        }

        // Try to Login first
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Login Success
                        checkAndSaveRole(role);
                    } else {
                        // If Login fails, try to Register (for hackathon demo purpose, combined)
                        mAuth.createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener(this, task2 -> {
                                    if (task2.isSuccessful()) {
                                        // Registration Success
                                        checkAndSaveRole(role);
                                    } else {
                                        progressBar.setVisibility(View.GONE);
                                        btnLogin.setEnabled(true);
                                        
                                        String errorMsg = task2.getException() != null ? task2.getException().getMessage() : "Unknown Error";
                                        Toast.makeText(this, "Auth Failed: " + errorMsg, Toast.LENGTH_LONG).show();
                                    }
                                });
                    }
                });
    }

    private void checkAndSaveRole(String role) {
        if (mAuth.getCurrentUser() == null) return;
        
        String uid = mAuth.getCurrentUser().getUid();
        
        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put("role", role);
        userProfile.put("email", mAuth.getCurrentUser().getEmail());

        db.collection("users").document(uid)
                .set(userProfile)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Welcome " + role, Toast.LENGTH_SHORT).show();
                    routeUser(role);
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    btnLogin.setEnabled(true);
                    Toast.makeText(this, "Failed to save profile: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void routeUser(String role) {
        if (role.equals("Guardian")) {
            startActivity(new Intent(this, GuardianActivity.class));
        } else {
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}