package com.ast2012a.clinicalmaster;

/**
 * رسالة واحدة في محادثة المساعد الذكي - إما من المستخدم أو من النموذج.
 * رسائل المساعد ممكن تحمل معلومات مصدر (قاعدة بيانات الجهاز و/أو Physiopedia)
 * لعرضها كشارة شفافة تحت الرد، وربط بسؤال المستخدم الأصلي لإتاحة إعادة
 * المحاولة (Regenerate) بدون تكرار سؤال المستخدم في المحادثة.
 */
public class ChatMessage {
    public static final int ROLE_USER = 0;
    public static final int ROLE_AI = 1;

    public int role;
    public String text;
    public String sourceLabel;   // مثال: "Physiopedia: الكتف المتجمد" - null لو مفيش مصدر خارجي
    public String sourceUrl;     // رابط المصدر (لفتحه في المتصفح) - اختياري
    public String relatedQuery;  // لرسائل المساعد فقط: نص سؤال المستخدم الأصلي
    public long timestamp;

    public ChatMessage(int role, String text) {
        this(role, text, null, null, null);
    }

    public ChatMessage(int role, String text, String sourceLabel, String sourceUrl, String relatedQuery) {
        this.role = role;
        this.text = text;
        this.sourceLabel = sourceLabel;
        this.sourceUrl = sourceUrl;
        this.relatedQuery = relatedQuery;
        this.timestamp = System.currentTimeMillis();
    }
}
