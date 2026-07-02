import smtplib
from email.message import EmailMessage

msg = EmailMessage()
msg['From'] = 'alice@example.com'
msg['To'] = 'bob@example.com'
msg['Subject'] = 'Hello from smtplib'
msg.set_content('This is a test message from Python.\nIt has multiple lines.\nEnd.')

with smtplib.SMTP('localhost', 1025) as s:
    s.set_debuglevel(1)  # prints the SMTP conversation
    s.send_message(msg)

print('\n--- Sent successfully ---')