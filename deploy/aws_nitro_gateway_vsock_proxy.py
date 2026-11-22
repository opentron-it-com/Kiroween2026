#!/usr/bin/env python3
"""Opaque TCP-to-vsock relay for TLS terminated inside a Nitro enclave."""

import logging
import os
import select
import socket
import socketserver
import threading
from typing import Callable

RELAY_BUFFER_BYTES = 64 * 1024
IDLE_TIMEOUT_SECONDS = 300
MAX_CONNECTIONS = 64


def _relay_connections(client: socket.socket, enclave: socket.socket) -> None:
    peers = {client: enclave, enclave: client}
    active = set(peers)
    client.setblocking(False)
    enclave.setblocking(False)
    while active:
        readable, _, exceptional = select.select(
            list(active), [], list(active), IDLE_TIMEOUT_SECONDS
        )
        if exceptional:
            raise OSError("Relay socket failed.")
        if not readable:
            raise TimeoutError("Relay connection was idle for too long.")
        for source in readable:
            destination = peers[source]
            payload = source.recv(RELAY_BUFFER_BYTES)
            if payload:
                destination.settimeout(IDLE_TIMEOUT_SECONDS)
                try:
                    destination.sendall(payload)
                finally:
                    destination.setblocking(False)
            else:
                active.remove(source)
                try:
                    destination.shutdown(socket.SHUT_WR)
                except OSError:
                    active.discard(destination)


def _connect_enclave(cid: int, port: int) -> socket.socket:
    if not hasattr(socket, "AF_VSOCK"):
        raise OSError("This host Python runtime does not support AF_VSOCK.")
    enclave = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
    try:
        enclave.settimeout(IDLE_TIMEOUT_SECONDS)
        enclave.connect((cid, port))
        return enclave
    except OSError:
        enclave.close()
        raise


class _GatewayHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        server = self.server
        try:
            with server.connect_enclave(server.vsock_cid, server.vsock_port) as enclave:
                self.request.settimeout(IDLE_TIMEOUT_SECONDS)
                _relay_connections(self.request, enclave)
        except (OSError, TimeoutError):
            logging.warning("AWS Nitro gateway relay connection failed.")


class _BoundedThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        handler: type[socketserver.BaseRequestHandler],
        vsock_cid: int,
        vsock_port: int,
        maximum_connections: int,
        connect_enclave: Callable[[int, int], socket.socket],
    ) -> None:
        self.vsock_cid = vsock_cid
        self.vsock_port = vsock_port
        self.connect_enclave = connect_enclave
        self._connection_slots = threading.BoundedSemaphore(maximum_connections)
        super().__init__(address, handler)

    def process_request(self, request: socket.socket, client_address: object) -> None:
        if not self._connection_slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._connection_slots.release()
            raise

    def process_request_thread(
        self, request: socket.socket, client_address: object
    ) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connection_slots.release()


def serve(
    connect_enclave: Callable[[int, int], socket.socket] = _connect_enclave,
) -> None:
    bind_host = os.environ.get("AWS_NITRO_GATEWAY_BIND", "127.0.0.1").strip()
    try:
        bind_port = int(os.environ.get("AWS_NITRO_GATEWAY_TCP_PORT", "8443"))
        vsock_cid = int(os.environ.get("AWS_NITRO_ENCLAVE_CID", "16"))
        vsock_port = int(os.environ.get("AWS_NITRO_GATEWAY_VSOCK_PORT", "8001"))
        maximum_connections = int(os.environ.get(
            "AWS_NITRO_GATEWAY_MAX_CONNECTIONS", str(MAX_CONNECTIONS)
        ))
    except ValueError as error:
        raise SystemExit("AWS Nitro gateway proxy port and CID values must be integers.") from error
    if not bind_host or not 1 <= bind_port <= 65535:
        raise SystemExit("AWS_NITRO_GATEWAY_BIND and TCP port must be configured.")
    if not 1 <= vsock_cid < 0xFFFFFFFF or not 1 <= vsock_port <= 65535:
        raise SystemExit("AWS Nitro enclave vsock CID and port are invalid.")
    if not 1 <= maximum_connections <= 1024:
        raise SystemExit("AWS_NITRO_GATEWAY_MAX_CONNECTIONS must be between 1 and 1024.")
    if not hasattr(socket, "AF_VSOCK"):
        raise SystemExit("The host Python runtime does not support AF_VSOCK.")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    with _BoundedThreadingTCPServer(
        (bind_host, bind_port),
        _GatewayHandler,
        vsock_cid,
        vsock_port,
        maximum_connections,
        connect_enclave,
    ) as server:
        logging.info(
            "Listening on %s:%s; relaying opaque TLS to enclave vsock CID %s port %s.",
            bind_host,
            bind_port,
            vsock_cid,
            vsock_port,
        )
        server.serve_forever()


if __name__ == "__main__":
    serve()
