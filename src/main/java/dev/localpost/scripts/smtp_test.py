import smtplib
from email.message import EmailMessage
from email.utils import make_msgid, formatdate
from pathlib import Path

msg = EmailMessage()

# Envelope-vs-headers demo: BCC recipient goes into RCPT TO
# but not into the visible headers
msg['From'] = 'Alice Sender <alice@example.com>'
msg['To'] = 'Bob Recipient <bob@example.com>, charlie@example.com'
msg['Cc'] = 'dana@example.com, eve@example.com'
msg['Subject'] = 'Rich test message - multipart, attachments, headers'
msg['Date'] = formatdate(localtime=True)
msg['Message-ID'] = make_msgid(domain='example.com')

# Plain text body
msg.set_content(
    'This is the plain-text version.\n'
    'It has multiple lines.\n'
    'End of plain text.'
)

# HTML alternative — this makes it multipart/alternative
msg.add_alternative(
    '<html><body>'
    '<h1>This is the HTML version</h1>'
    '<p>With <strong>formatting</strong> and a '
    '<a href="https://example.com">link</a>.</p>'
    '</body></html>',
    subtype='html'
)

# A tiny attachment — makes it multipart/mixed wrapping the above
attachment_bytes = b'Hello from an attachment file.\nJust some plain text.\n'
msg.add_attachment(
    attachment_bytes,
    maintype='text',
    subtype='plain',
    filename='notes.txt'
)

# BCC goes on the transaction, not the message headers
bcc = ['frank-secret@example.com']

with smtplib.SMTP('localhost', 1025) as s:
    s.set_debuglevel(1)
    # send_message lets us pass a to_addrs list — this becomes the
    # actual RCPT TO commands, distinct from what's in the headers
    to_addrs = ['bob@example.com', 'charlie@example.com',
                'dana@example.com', 'eve@example.com'] + bcc
    s.send_message(msg, to_addrs=to_addrs)

print('\n--- Sent successfully ---')