package com.revesoft.tms.user;

import com.revesoft.tms.common.BusinessException;
import java.security.SecureRandom;

public final class PasswordPolicy {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException("Password must be at least 8 characters");
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException("Password must contain letters and numbers");
        }
    }

    /** A random temporary password that satisfies the policy. */
    public static String temporary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        for (int i = 0; i < 3; i++) {
            sb.insert(RANDOM.nextInt(sb.length() + 1), DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }
}
