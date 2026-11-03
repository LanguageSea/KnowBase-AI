# TLS Certificates

`compose.prod.yml` mounts this directory at `/etc/nginx/tls`.

For a local acceptance run the Nginx entrypoint generates a self-signed
`tls.crt` / `tls.key` here. For a real deployment replace both files with a
trusted certificate and private key, then restart the `nginx` service.

Never commit a real private key. Files matching `deploy/certs/*.key` and
`deploy/certs/*.pem` are ignored by Git.
