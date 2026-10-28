// Hopp's feed proxy (ADR 0022, 6): the browser may read a feed, chapters,
// a transcript or an image only from a host that allows it (CORS), and most
// podcast hosts do not. This fetches them for the web client — GET only, never
// for a private address, never with cookies or credentials, within size
// limits — and answers with CORS for the web client's origins only.
//
// A service of its own, apart from Sovereigncast: a request forgery through it
// (SSRF) must not reach anything but the public internet.
//
// GET /fetch?url=<http(s) URL>   GET /healthz
package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	maxFeedBytes  = 32 << 20 // a feed, chapters, a transcript: as much as the apps read (Limits.FEED)
	maxImageBytes = 5 << 20
	maxRedirects  = 5
	fetchTimeout  = 30 * time.Second
	userAgent     = "Hopp (Web; feed proxy)"
)

// Proxy answers /fetch. Origins: the web client's, allowed by CORS.
type Proxy struct {
	Origins map[string]bool
	Client  *http.Client
	Limiter *Limiter
}

func main() {
	addr := envOr("HOPP_PROXY_ADDR", ":8643")
	origins := map[string]bool{}
	for _, o := range strings.Split(envOr("HOPP_PROXY_ORIGINS", "http://127.0.0.1:8642,http://localhost:8642"), ",") {
		if o = strings.TrimSpace(o); o != "" {
			origins[o] = true
		}
	}
	p := &Proxy{Origins: origins, Client: NewClient(PublicOnly), Limiter: NewLimiter(120, time.Minute)}
	srv := &http.Server{Addr: addr, Handler: p, ReadHeaderTimeout: 10 * time.Second, WriteTimeout: 2 * fetchTimeout}
	log.Printf("feed proxy on %s for %d origins", addr, len(origins))
	log.Fatal(srv.ListenAndServe())
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// PublicOnly refuses every address that is not on the public internet:
// loopback, private, link-local, CGNAT, multicast, unspecified, documentation.
func PublicOnly(a netip.Addr) bool {
	a = a.Unmap()
	if !a.IsValid() || a.IsLoopback() || a.IsPrivate() || a.IsLinkLocalUnicast() || a.IsLinkLocalMulticast() ||
		a.IsMulticast() || a.IsUnspecified() || a.IsInterfaceLocalMulticast() {
		return false
	}
	for _, p := range blocked {
		if p.Contains(a) {
			return false
		}
	}
	return true
}

var blocked = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"), // CGNAT, Tailscale
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("64:ff9b::/96"), // NAT64 can point into a private network
	netip.MustParsePrefix("2001:db8::/32"),
}

// NewClient dials only addresses [allowed] accepts — checked on the address
// actually connected to, after DNS, so a name that resolves (or re-resolves)
// to a private address is refused too. Redirects are followed by hand.
func NewClient(allowed func(netip.Addr) bool) *http.Client {
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, _ syscall.RawConn) error {
			host, _, err := net.SplitHostPort(address)
			if err != nil {
				return err
			}
			a, err := netip.ParseAddr(host)
			if err != nil || !allowed(a) {
				return errRefused
			}
			return nil
		},
	}
	tr := &http.Transport{
		DialContext:           dialer.DialContext,
		Proxy:                 nil,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 15 * time.Second,
		MaxIdleConns:          32,
		IdleConnTimeout:       60 * time.Second,
	}
	return &http.Client{
		Transport:     tr,
		Timeout:       fetchTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

var errRefused = errors.New("address not allowed")

func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("Content-Security-Policy", "default-src 'none'; sandbox")
	h.Set("Cross-Origin-Resource-Policy", "cross-origin")
	if o := r.Header.Get("Origin"); o != "" && p.Origins[o] {
		h.Set("Access-Control-Allow-Origin", o)
		h.Set("Vary", "Origin")
		h.Set("Access-Control-Expose-Headers", "ETag, Last-Modified")
	}
	switch {
	case r.URL.Path == "/healthz":
		io.WriteString(w, "ok")
		return
	case r.URL.Path != "/fetch":
		http.Error(w, "not found", http.StatusNotFound)
		return
	case r.Method == http.MethodOptions:
		h.Set("Access-Control-Allow-Methods", "GET")
		h.Set("Access-Control-Allow-Headers", "If-None-Match, If-Modified-Since, Accept")
		h.Set("Access-Control-Max-Age", "86400")
		w.WriteHeader(http.StatusNoContent)
		return
	case r.Method != http.MethodGet:
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	if !p.Limiter.Allow(clientIP(r)) {
		h.Set("Retry-After", "60")
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	target, err := checkURL(r.URL.Query().Get("url"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	p.fetch(w, r, target)
}

func checkURL(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil {
		return nil, errors.New("an http or https URL without credentials")
	}
	return u, nil
}

func (p *Proxy) fetch(w http.ResponseWriter, r *http.Request, target *url.URL) {
	ctx, cancel := context.WithTimeout(r.Context(), fetchTimeout)
	defer cancel()
	var resp *http.Response
	for hop := 0; ; hop++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
		if err != nil {
			http.Error(w, "bad url", http.StatusBadRequest)
			return
		}
		// Only what makes a fetch cheaper; never the listener's cookies or credentials.
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("Accept", orDefault(r.Header.Get("Accept"), "*/*"))
		for _, k := range []string{"If-None-Match", "If-Modified-Since"} {
			if v := r.Header.Get(k); v != "" {
				req.Header.Set(k, v)
			}
		}
		resp, err = p.Client.Do(req)
		if err != nil {
			if errors.Is(err, errRefused) {
				http.Error(w, "address not allowed", http.StatusForbidden)
			} else {
				http.Error(w, "upstream unreachable", http.StatusBadGateway)
			}
			return
		}
		if resp.StatusCode < 300 || resp.StatusCode >= 400 || resp.StatusCode == http.StatusNotModified {
			break
		}
		resp.Body.Close()
		next, err := target.Parse(resp.Header.Get("Location"))
		if err != nil || hop >= maxRedirects {
			http.Error(w, "too many or bad redirects", http.StatusBadGateway)
			return
		}
		if target, err = checkURL(next.String()); err != nil {
			http.Error(w, "redirect to a non-http URL", http.StatusBadGateway)
			return
		}
	}
	defer resp.Body.Close()

	ct := resp.Header.Get("Content-Type")
	limit, ok := allowedType(ct)
	if resp.StatusCode == http.StatusNotModified {
		ok, limit = true, 0
	}
	if !ok {
		http.Error(w, "content type not allowed: "+ct, http.StatusUnsupportedMediaType)
		return
	}
	if resp.ContentLength > limit && resp.StatusCode != http.StatusNotModified {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		http.Error(w, "upstream read failed", http.StatusBadGateway)
		return
	}
	if int64(len(body)) > limit {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}
	h := w.Header()
	if ct != "" {
		h.Set("Content-Type", ct)
	}
	for _, k := range []string{"ETag", "Last-Modified"} {
		if v := resp.Header.Get(k); v != "" {
			h.Set(k, v)
		}
	}
	h.Set("Cache-Control", "public, max-age=60")
	h.Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(resp.StatusCode)
	w.Write(body)
}

// allowedType: feeds and their parts (XML, JSON, text such as VTT, SRT and HTML
// transcripts) and images; with the size each may have. Many hosts label a feed
// application/octet-stream — taken as a feed: with nosniff and the sandbox CSP
// nothing here is ever run.
func allowedType(ct string) (int64, bool) {
	t := strings.ToLower(strings.TrimSpace(strings.SplitN(ct, ";", 2)[0]))
	switch {
	case strings.HasPrefix(t, "image/"):
		return maxImageBytes, true
	case t == "", strings.HasPrefix(t, "text/"), strings.HasSuffix(t, "/xml"), strings.HasSuffix(t, "+xml"),
		strings.HasSuffix(t, "/json"), strings.HasSuffix(t, "+json"), t == "application/x-subrip", t == "application/octet-stream":
		return maxFeedBytes, true
	}
	return 0, false
}

func orDefault(v, d string) string {
	if v == "" {
		return d
	}
	return v
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// Limiter: at most n requests per window and address. No logs, no history.
type Limiter struct {
	mu     sync.Mutex
	n      int
	window time.Duration
	seen   map[string]*bucket
}

type bucket struct {
	start time.Time
	count int
}

func NewLimiter(n int, window time.Duration) *Limiter {
	return &Limiter{n: n, window: window, seen: map[string]*bucket{}}
}

func (l *Limiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.seen) > 10_000 {
		for k, b := range l.seen {
			if now.Sub(b.start) > l.window {
				delete(l.seen, k)
			}
		}
	}
	b := l.seen[key]
	if b == nil || now.Sub(b.start) > l.window {
		l.seen[key] = &bucket{start: now, count: 1}
		return true
	}
	b.count++
	return b.count <= l.n
}
