import socket
import threading
import unittest

from deploy import aws_nitro_gateway_vsock_proxy as proxy


class NitroGatewayVsockProxyTests(unittest.TestCase):
    def test_relays_bidirectional_tls_bytes_without_interpreting_them(self) -> None:
        host_client, proxy_tcp = socket.socketpair()
        proxy_vsock, enclave_client = socket.socketpair()
        relay = threading.Thread(
            target=proxy._relay_connections,
            args=(proxy_tcp, proxy_vsock),
            daemon=True,
        )
        relay.start()
        try:
            client_record = b"\x16\x03\x01client-tls-record"
            enclave_record = b"\x16\x03\x03enclave-tls-record"
            host_client.sendall(client_record)
            self.assertEqual(enclave_client.recv(len(client_record)), client_record)
            enclave_client.sendall(enclave_record)
            self.assertEqual(host_client.recv(len(enclave_record)), enclave_record)
            host_client.shutdown(socket.SHUT_WR)
            self.assertEqual(enclave_client.recv(1), b"")
            enclave_client.shutdown(socket.SHUT_WR)
            self.assertEqual(host_client.recv(1), b"")
            relay.join(timeout=2)
            self.assertFalse(relay.is_alive())
        finally:
            host_client.close()
            proxy_tcp.close()
            proxy_vsock.close()
            enclave_client.close()


if __name__ == "__main__":
    unittest.main()
