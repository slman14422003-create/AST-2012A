package com.ast2012a.clinicalmaster;

/**
 * رسالة واحدة في محادثة المساعد الذكي - إما من المستخدم أو من النموذج.
 */
public class ChatMessage {
    public static final int ROLE_USER = 0;
    public static final int ROLE_AI = 1;

    public int role;
    public String text;

    public ChatMessage(int role, String text) {
        this.role = role;
        this.text = text;
    }
}
