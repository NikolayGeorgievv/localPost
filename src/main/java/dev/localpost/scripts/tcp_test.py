import socket

with socket.create_connection(('localhost', 1025)) as s:
    s.sendall(b'hello world\r\n')
    response = s.recv(1024)
    print('Server replied:', response.decode())