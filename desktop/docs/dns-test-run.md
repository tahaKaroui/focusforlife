# DNS Test Run

This is the narrowest real enforcement path currently supported by the repo:

- generate a managed Unbound config
- generate a managed Unbound blocklist
- generate nftables rules that redirect local DNS to Unbound and reject DoT on port 853

## Generate assets

Run from the repo root:

```bash
cargo run -p ffl-daemon -- --write-dns-test-assets /tmp/ffl-dns-test
```

This writes:

- `/tmp/ffl-dns-test/unbound.conf`
- `/tmp/ffl-dns-test/focusforlife-blocklist.conf`
- `/tmp/ffl-dns-test/ffl-dns.nft`

## Activate on a machine with Unbound installed

Install Unbound if missing:

```bash
sudo apt-get update
sudo apt-get install -y unbound
```

Copy generated files into place:

```bash
sudo install -d /etc/focusforlife
sudo install -m 644 /tmp/ffl-dns-test/unbound.conf /etc/focusforlife/unbound.conf
sudo install -m 644 /tmp/ffl-dns-test/focusforlife-blocklist.conf /etc/focusforlife/focusforlife-blocklist.conf
sudo install -m 644 /tmp/ffl-dns-test/ffl-dns.nft /etc/focusforlife/ffl-dns.nft
```

Start a dedicated Unbound instance:

```bash
sudo unbound -c /etc/focusforlife/unbound.conf
```

Apply nftables rules:

```bash
sudo nft -f /etc/focusforlife/ffl-dns.nft
```

## Verify

These should fail for blocked domains:

```bash
dig @127.0.0.1 -p 5335 site-a.example.com
dig @127.0.0.1 -p 5335 site-b.example.com
```

Then check browser access to a blocked site.

## Roll back

Stop the dedicated Unbound instance and remove the nftables table:

```bash
sudo pkill -f 'unbound -c /etc/focusforlife/unbound.conf'
sudo nft delete table inet focusforlife_dns
```

## Current limits

- This is a DNS enforcement test path, not the final full daemon-managed install flow.
- DoH blocking is not implemented yet.
- The UI is not yet connected to this runtime path.
