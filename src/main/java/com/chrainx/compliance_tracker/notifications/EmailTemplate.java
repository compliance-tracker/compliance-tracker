package com.chrainx.compliance_tracker.notifications;

// A small, shared HTML shell for the auth emails (EmailAuthEmailSender) - deliberately simple,
// table-based-free, inline-styled-only HTML, since email clients (Outlook especially) strip
// <style> blocks and don't support most modern CSS. Colors loosely echo the frontend's own
// "Harbour Ledger" teal/brass palette (frontend's index.css), translated to plain hex since email
// clients don't support oklch() at all.
final class EmailTemplate {

    private static final String TEAL = "#1f4b4d";
    private static final String TEAL_DARK = "#163536";
    private static final String BRASS = "#b98f4f";
    private static final String INK = "#1c2b2c";
    private static final String MUTED = "#5c6b6c";
    private static final String BACKGROUND = "#eef3f3";
    private static final String CARD = "#ffffff";
    private static final String HAIRLINE = "#dbe4e4";

    private EmailTemplate() {
    }

    // heading: the card's own title (distinct from the email Subject, shown again inside the
    // body so the message reads correctly even in a client's preview pane that truncates
    // subjects). bodyText: one or two sentences of plain explanation. buttonText/buttonUrl: the
    // one real action this email exists for. footerNote: a short reassurance/disclaimer line.
    static String render(String heading, String bodyText, String buttonText, String buttonUrl, String footerNote) {
        return """
                <!doctype html>
                <html>
                <body style="margin:0; padding:32px 16px; background-color:%s; \
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="480" cellpadding="0" cellspacing="0" \
                style="max-width:480px; width:100%%; background-color:%s; border-radius:12px; \
                border-top:4px solid %s; box-shadow:0 1px 3px rgba(28,43,44,0.08);">
                          <tr>
                            <td style="padding:32px 32px 8px 32px;">
                              <div style="font-size:13px; font-weight:600; letter-spacing:0.08em; \
                text-transform:uppercase; color:%s;">Compliance Tracker</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:8px 32px 0 32px;">
                              <h1 style="margin:0 0 16px 0; font-size:22px; line-height:1.3; color:%s;">%s</h1>
                              <p style="margin:0 0 28px 0; font-size:15px; line-height:1.6; color:%s;">%s</p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px;">
                              <table role="presentation" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="border-radius:8px; background-color:%s;">
                                    <a href="%s" style="display:inline-block; padding:12px 28px; \
                font-size:15px; font-weight:600; color:#fff8ee; text-decoration:none; border-radius:8px;">%s</a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 32px 0 32px;">
                              <p style="margin:0; font-size:12px; line-height:1.6; color:%s; word-break:break-all;">\
                Or copy and paste this link: <a href="%s" style="color:%s;">%s</a></p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:28px 32px 32px 32px;">
                              <hr style="border:none; border-top:1px solid %s; margin:0 0 20px 0;" />
                              <p style="margin:0; font-size:12px; line-height:1.6; color:%s;">%s</p>
                              <p style="margin:16px 0 0 0; font-size:11px; line-height:1.6; color:%s;">\
                This is a reminder/tracking tool, not compliance advice - always verify against the official \
                ACRA/IRAS/MOM source for anything time-sensitive.</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                BACKGROUND, CARD, BRASS,
                TEAL,
                INK, heading,
                MUTED, bodyText,
                TEAL_DARK, buttonUrl, buttonText,
                MUTED, buttonUrl, TEAL, buttonUrl,
                HAIRLINE,
                MUTED, footerNote,
                MUTED);
    }
}
