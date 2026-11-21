// Sends a one-time code only when a mailer is injected (tests) or both
// RESEND_API_KEY and EMAIL_FROM are set. Otherwise the caller returns 503.
// The code is never written to a log.

export async function deliverCode(env, email, code) {
  if (typeof env.MAILER === 'function') {
    await env.MAILER({ email, code })
    return
  }
  const key = env.RESEND_API_KEY
  const from = env.EMAIL_FROM
  if (!key || !from) {
    const err = new Error('email_not_configured')
    err.status = 503
    err.code = 'email_not_configured'
    throw err
  }
  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${key}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      from,
      to: [email],
      subject: 'Your OpenWild sign-in code',
      text: `Your OpenWild code is ${code}. It expires in 15 minutes. If you did not ask for it, ignore this message.`,
    }),
  })
  if (!res.ok) {
    const err = new Error('email_failed')
    err.status = 502
    err.code = 'email_failed'
    throw err
  }
}
