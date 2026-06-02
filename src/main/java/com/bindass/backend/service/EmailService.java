package com.bindass.backend.service;

import com.bindass.backend.model.Order;
import com.bindass.backend.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from}")
    private String fromEmail;

    @Value("${app.client-url}")
    private String clientUrl;

    // ── Base HTML wrapper (dark BINDASS theme) ────────────────
    private String wrap(String content) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"/>
            <style>
              body{background:#0A0A0A;color:#F5F5F0;font-family:'DM Sans',sans-serif;margin:0}
              .container{max-width:600px;margin:0 auto}
              .header{padding:32px 40px;border-bottom:1px solid #1C1C1C}
              .logo{font-size:28px;font-weight:700;letter-spacing:6px;color:#F5F5F0;
                    text-decoration:none;text-transform:uppercase}
              .body{padding:48px 40px}
              .footer{border-top:1px solid #1C1C1C;padding:24px 40px;text-align:center}
              .footer p{font-size:11px;color:#5C5C5C;letter-spacing:1px}
              .btn{display:inline-block;background:#F5F5F0;color:#0A0A0A;text-decoration:none;
                   font-size:11px;font-weight:600;letter-spacing:3px;text-transform:uppercase;
                   padding:14px 32px;margin-top:24px}
              .divider{border:none;border-top:1px solid #1C1C1C;margin:32px 0}
              .label{font-size:10px;letter-spacing:3px;color:#5C5C5C;text-transform:uppercase;margin-bottom:6px}
              .tag{display:inline-block;background:#1C1C1C;color:#ADADAD;font-size:10px;
                   letter-spacing:2px;text-transform:uppercase;padding:4px 10px}
            </style></head>
            <body><div class="container">
              <div class="header"><a href="%s" class="logo">BINDASS</a></div>
              <div class="body">%s</div>
              <div class="footer"><p>© 2024 Bindass.co · Crafted in India 🖤</p></div>
            </div></body></html>
            """.formatted(clientUrl, content);
    }

    // ── Send helper ───────────────────────────────────────────
    private void send(String to, String subject, String htmlContent) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true); // true = HTML
        mailSender.send(message);
        log.info("Email sent to {} — {}", to, subject);
    }

    // ── 1. Welcome email ──────────────────────────────────────
    public void sendWelcomeEmail(User user) throws Exception {
        String content = """
            <h1 style="font-size:36px;letter-spacing:4px;text-transform:uppercase;margin-bottom:12px">
              Welcome, %s.
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8;margin-bottom:24px">
              You're now part of the BINDASS inner circle.<br/>
              Precision-tailored pieces, dark luxury streetwear, early access to every drop.
            </p>
            <a href="%s/shop" class="btn">Shop Now</a>
            """.formatted(user.getName().split(" ")[0], clientUrl);

        send(user.getEmail(), "Welcome to BINDASS 🖤", wrap(content));
    }

    // ── 2. Order confirmation ─────────────────────────────────
    public void sendOrderConfirmation(User user, Order order) throws Exception {
        String itemRows = order.getItems().stream()
                .map(i -> "<tr><td>%s</td><td>%s</td><td>%d</td><td>₹%,.0f</td></tr>"
                        .formatted(i.getName(), i.getSize(), i.getQuantity(),
                                i.getPrice() * i.getQuantity()))
                .collect(Collectors.joining());

        String orderId = order.getId().substring(
                Math.max(0, order.getId().length() - 8)
        ).toUpperCase();

        String content = """
            <span class="tag">Order Confirmed</span>
            <h1 style="font-size:32px;letter-spacing:4px;text-transform:uppercase;margin:20px 0 8px">
              Order Placed.
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8">
              Hey %s, your order is confirmed and will be dispatched within 2-3 business days.
            </p>
            <table style="width:100%%;border-collapse:collapse;margin-top:24px">
              <thead>
                <tr style="border-bottom:1px solid #1C1C1C">
                  <th style="text-align:left;padding:8px 0;font-size:10px;letter-spacing:2px;
                             color:#5C5C5C;text-transform:uppercase">Item</th>
                  <th style="font-size:10px;letter-spacing:2px;color:#5C5C5C">Size</th>
                  <th style="font-size:10px;letter-spacing:2px;color:#5C5C5C">Qty</th>
                  <th style="text-align:right;font-size:10px;letter-spacing:2px;color:#5C5C5C">Price</th>
                </tr>
              </thead>
              <tbody style="color:#ADADAD;font-size:13px">%s</tbody>
              <tfoot>
                <tr style="color:#F5F5F0;font-weight:600">
                  <td colspan="3" style="padding-top:16px">Total</td>
                  <td style="text-align:right;padding-top:16px">₹%,.0f</td>
                </tr>
              </tfoot>
            </table>
            <hr class="divider"/>
            <p class="label">Order ID</p>
            <p style="font-size:14px;color:#F5F5F0">#%s</p>
            <a href="%s/orders" class="btn">Track Order</a>
            """.formatted(
                user.getName().split(" ")[0], itemRows,
                order.getTotalAmount(), orderId, clientUrl
        );

        send(user.getEmail(),
                "Order Confirmed — #" + orderId,
                wrap(content));
    }

    // ── 3. Shipped ────────────────────────────────────────────
    public void sendShippingUpdate(User user, Order order) throws Exception {
        String orderId = order.getId().substring(
                Math.max(0, order.getId().length() - 8)
        ).toUpperCase();

        String trackingHtml = order.getTrackingNumber() != null
                ? "<p class=\"label\">Tracking</p><p style=\"color:#F5F5F0\">%s via %s</p>"
                .formatted(order.getTrackingNumber(),
                        order.getCarrier() != null ? order.getCarrier() : "courier")
                : "";

        String content = """
            <span class="tag">Shipped</span>
            <h1 style="font-size:32px;letter-spacing:4px;text-transform:uppercase;margin:20px 0 8px">
              It's on its way.
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8">
              Your BINDASS order #%s is packed and handed to our shipping partner.
            </p>
            <hr class="divider"/>
            %s
            <a href="%s/orders" class="btn">View Order</a>
            """.formatted(orderId, trackingHtml, clientUrl);

        send(user.getEmail(), "Your BINDASS order is shipped 🚚", wrap(content));
    }

    // ── 4. Delivered ──────────────────────────────────────────
    public void sendDeliveryConfirmation(User user, Order order) throws Exception {
        String content = """
            <span class="tag">Delivered</span>
            <h1 style="font-size:32px;letter-spacing:4px;text-transform:uppercase;margin:20px 0 8px">
              Delivered. Style up.
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8;margin-bottom:32px">
              Your order has been delivered. We hope you love it.<br/>
              Drop a review — it means the world to us.
            </p>
            <a href="%s/orders" class="btn">Leave a Review</a>
            """.formatted(clientUrl);

        send(user.getEmail(), "Your BINDASS order has been delivered 🖤", wrap(content));
    }

    // ── 5. Cancellation ───────────────────────────────────────
    public void sendCancellationEmail(User user, Order order) throws Exception {
        String orderId = order.getId().substring(
                Math.max(0, order.getId().length() - 8)
        ).toUpperCase();

        String refundNote = order.getPaymentStatus().name().equals("REFUNDED")
                ? " A refund of <strong style=\"color:#C8B89A\">₹%,.0f</strong> will be credited within 5-7 days."
                .formatted(order.getTotalAmount())
                : "";

        String content = """
            <span class="tag">Cancelled</span>
            <h1 style="font-size:32px;letter-spacing:4px;text-transform:uppercase;margin:20px 0 8px">
              Order Cancelled.
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8">
              Your order <strong style="color:#F5F5F0">#%s</strong> has been cancelled.%s
            </p>
            <a href="%s/shop" class="btn">Shop Again</a>
            """.formatted(orderId, refundNote, clientUrl);

        send(user.getEmail(), "Order Cancelled — #" + orderId, wrap(content));
    }

    // ── 6. Password reset ─────────────────────────────────────
    public void sendPasswordResetEmail(User user, String resetUrl) throws Exception {
        String content = """
            <h1 style="font-size:32px;letter-spacing:4px;text-transform:uppercase;margin-bottom:12px">
              Reset Password
            </h1>
            <p style="font-size:14px;color:#8C8C8C;line-height:1.8;margin-bottom:8px">
              You requested a password reset. This link expires in 15 minutes.
            </p>
            <a href="%s" class="btn">Reset Password</a>
            <hr class="divider"/>
            <p style="font-size:12px;color:#5C5C5C">
              Didn't request this? Ignore this email. Your account is safe.
            </p>
            """.formatted(resetUrl);

        send(user.getEmail(), "BINDASS — Reset Your Password", wrap(content));
    }
}