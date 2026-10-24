import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

type AuthHookPayload = {
  user?: { email?: string };
  email_data?: {
    token?: string;
    token_hash?: string;
    redirect_to?: string;
    email_action_type?: string;
  };
};

const json = (body: Record<string, string>, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const hookSecret = Deno.env.get("SEND_EMAIL_HOOK_SECRET")?.replace(/^v1,whsec_/, "");
  const apiKeySid = Deno.env.get("TWILIO_API_KEY_SID");
  const apiKeySecret = Deno.env.get("TWILIO_API_KEY_SECRET");
  const fromAddress = Deno.env.get("TWILIO_FROM_EMAIL");
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  if (!hookSecret || !apiKeySid || !apiKeySecret || !fromAddress || !supabaseUrl) {
    console.error("Auth email hook is missing required secrets.");
    return json({ error: "service_unavailable" }, 503);
  }

  const rawBody = await request.text();
  let payload: AuthHookPayload;
  try {
    const webhook = new Webhook(hookSecret);
    payload = webhook.verify(rawBody, Object.fromEntries(request.headers)) as AuthHookPayload;
  } catch {
    return json({ error: "invalid_hook_signature" }, 401);
  }

  const email = payload.user?.email?.trim();
  const emailData = payload.email_data;
  const action = emailData?.email_action_type;
  if (!email || !emailData || !action) return json({ error: "invalid_hook_payload" }, 400);

  let subject = "Aviso de seguridad de TzindaApp";
  let text = "Se solicitó una acción de seguridad para tu cuenta de TzindaApp.";
  let html = "<p>Se solicitó una acción de seguridad para tu cuenta de TzindaApp.</p>";

  if (action === "signup" || action === "magiclink") {
    const token = emailData.token?.trim();
    if (!token || !/^\d{6,8}$/.test(token)) return json({ error: "otp_unavailable" }, 502);
    subject = "Tu código de verificación de TzindaApp";
    text = `Tu código de verificación es: ${token}\n\nEscríbelo en TzindaApp para continuar. Si no solicitaste este código, ignora este correo.`;
    html = `<div style="font-family:Arial,sans-serif;color:#1c1c15"><h2>Verifica tu correo en TzindaApp</h2><p>Escribe este código en la app para continuar:</p><p style="font-size:32px;font-weight:700;letter-spacing:8px">${token}</p><p>Si no solicitaste este código, ignora este correo.</p></div>`;
  } else if (action === "recovery" || action === "invite" || action === "email_change") {
    const tokenHash = emailData.token_hash?.trim();
    if (!tokenHash) return json({ error: "verification_link_unavailable" }, 502);
    const verificationUrl = new URL("/auth/v1/verify", supabaseUrl);
    verificationUrl.searchParams.set("token", tokenHash);
    verificationUrl.searchParams.set("type", action);
    if (emailData.redirect_to) verificationUrl.searchParams.set("redirect_to", emailData.redirect_to);
    const url = verificationUrl.toString();
    subject = action === "recovery" ? "Restablece tu contraseña de TzindaApp" : "Confirma una acción de TzindaApp";
    text = `Abre este enlace para continuar: ${url}\n\nSi no solicitaste esta acción, ignora este correo.`;
    html = `<div style="font-family:Arial,sans-serif;color:#1c1c15"><h2>${subject}</h2><p><a href="${url}">Continuar en TzindaApp</a></p><p>Si no solicitaste esta acción, ignora este correo.</p></div>`;
  }

  let response: Response;
  try {
    response = await fetch("https://comms.twilio.com/v1/Emails", {
      method: "POST",
      headers: {
        "Authorization": `Basic ${btoa(`${apiKeySid}:${apiKeySecret}`)}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: { address: fromAddress, name: "TzindaApp" },
        to: [{ address: email }],
        content: { subject, text, html },
      }),
    });
  } catch {
    console.error("Twilio Email request failed before receiving a response.");
    return json({ error: "email_provider_unavailable" }, 502);
  }

  if (response.status !== 202) {
    console.error(`Twilio Email returned HTTP ${response.status}.`);
    return json({ error: "email_delivery_rejected" }, 502);
  }

  return json({});
});
