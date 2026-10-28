package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"
)

const origin = "http://127.0.0.1:8642"

// onlyFirstLoopback: 127.0.0.1 stands for "the public internet" in these tests; every other
// address — 127.0.0.2 included — is a private one.
func onlyFirstLoopback(a netip.Addr) bool { return a.Unmap() == netip.MustParseAddr("127.0.0.1") }

func proxy(allowed func(netip.Addr) bool) *Proxy {
	return &Proxy{Origins: map[string]bool{origin: true}, Client: NewClient(allowed), Limiter: NewLimiter(1000, time.Minute)}
}

func get(p *Proxy, target string, hdr map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodGet, "/fetch?url="+url.QueryEscape(target), nil)
	r.Header.Set("Origin", origin)
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	p.ServeHTTP(w, r)
	return w
}

func TestPublicOnlyRefusesEveryPrivateAddress(t *testing.T) {
	for _, a := range []string{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.100.1.1",
		"0.0.0.0", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "224.0.0.1", "198.18.0.1"} {
		if PublicOnly(netip.MustParseAddr(a)) {
			t.Errorf("%s should be refused", a)
		}
	}
	for _, a := range []string{"1.1.1.1", "93.184.216.34", "2606:4700:4700::1111"} {
		if !PublicOnly(netip.MustParseAddr(a)) {
			t.Errorf("%s should be allowed", a)
		}
	}
}

func TestALoopbackHostIsRefusedWithTheRealRules(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("secret")) }))
	defer up.Close()
	w := get(proxy(PublicOnly), up.URL, nil)
	if w.Code != http.StatusForbidden || strings.Contains(w.Body.String(), "secret") {
		t.Fatalf("got %d %q", w.Code, w.Body.String())
	}
	// A name that resolves to it is refused all the same: the check is on the address dialled.
	w = get(proxy(PublicOnly), strings.Replace(up.URL, "127.0.0.1", "localhost", 1), nil)
	if w.Code != http.StatusForbidden {
		t.Fatalf("localhost: got %d", w.Code)
	}
}

func TestAFeedComesThroughWithCorsAndNothingOfTheListeners(t *testing.T) {
	var seen http.Header
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Clone()
		w.Header().Set("Content-Type", "application/rss+xml")
		w.Header().Set("Set-Cookie", "track=1")
		w.Header().Set("ETag", `"v1"`)
		w.Write([]byte("<rss/>"))
	}))
	defer up.Close()
	w := get(proxy(onlyFirstLoopback), up.URL, map[string]string{"Cookie": "session=1", "Authorization": "Bearer x", "If-None-Match": `"v0"`})
	if w.Code != 200 || w.Body.String() != "<rss/>" {
		t.Fatalf("got %d %q", w.Code, w.Body.String())
	}
	if w.Header().Get("Access-Control-Allow-Origin") != origin || w.Header().Get("Set-Cookie") != "" || w.Header().Get("ETag") != `"v1"` {
		t.Fatalf("headers %v", w.Header())
	}
	if w.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("no nosniff")
	}
	if seen.Get("Cookie") != "" || seen.Get("Authorization") != "" || seen.Get("If-None-Match") != `"v0"` {
		t.Fatalf("sent %v", seen)
	}
}

func TestAnotherOriginGetsNoCors(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("x")) }))
	defer up.Close()
	r := httptest.NewRequest(http.MethodGet, "/fetch?url="+url.QueryEscape(up.URL), nil)
	r.Header.Set("Origin", "https://evil.example")
	w := httptest.NewRecorder()
	proxy(onlyFirstLoopback).ServeHTTP(w, r)
	if w.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("CORS for a foreign origin")
	}
}

func TestARedirectIntoThePrivateNetworkIsRefused(t *testing.T) {
	inner := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("admin")) }))
	l, err := netListen("127.0.0.2:0")
	if err != nil {
		t.Skip("no 127.0.0.2 here:", err)
	}
	inner.Listener = l
	inner.Start()
	defer inner.Close()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, inner.URL, http.StatusFound) }))
	defer up.Close()
	w := get(proxy(onlyFirstLoopback), up.URL, nil)
	if w.Code != http.StatusForbidden || strings.Contains(w.Body.String(), "admin") {
		t.Fatalf("got %d %q", w.Code, w.Body.String())
	}
}

func TestRedirectsAreFollowedWithinLimits(t *testing.T) {
	var up *httptest.Server
	up = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/a":
			http.Redirect(w, r, "/b", http.StatusMovedPermanently)
		case "/b":
			w.Header().Set("Content-Type", "text/xml")
			w.Write([]byte("<rss/>"))
		case "/loop":
			http.Redirect(w, r, "/loop", http.StatusFound)
		case "/file":
			http.Redirect(w, r, "file:///etc/passwd", http.StatusFound)
		}
	}))
	defer up.Close()
	p := proxy(onlyFirstLoopback)
	if w := get(p, up.URL+"/a", nil); w.Code != 200 || w.Body.String() != "<rss/>" {
		t.Fatalf("a: %d %q", w.Code, w.Body.String())
	}
	if w := get(p, up.URL+"/loop", nil); w.Code != http.StatusBadGateway {
		t.Fatalf("loop: %d", w.Code)
	}
	if w := get(p, up.URL+"/file", nil); w.Code != http.StatusBadGateway {
		t.Fatalf("file: %d", w.Code)
	}
}

func TestOnlyHttpUrlsWithoutCredentials(t *testing.T) {
	p := proxy(onlyFirstLoopback)
	for _, u := range []string{"", "file:///etc/passwd", "gopher://x", "ftp://x/y", "http://user:pw@example.com/", "//example.com/feed"} {
		if w := get(p, u, nil); w.Code != http.StatusBadRequest {
			t.Errorf("%q: %d", u, w.Code)
		}
	}
}

func TestTypesAndSizes(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/script":
			w.Header().Set("Content-Type", "application/javascript")
			w.Write([]byte("alert(1)"))
		case "/big-image":
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(make([]byte, maxImageBytes+1))
		case "/image":
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(make([]byte, 1000))
		case "/vtt":
			w.Header().Set("Content-Type", "text/vtt")
			w.Write([]byte("WEBVTT"))
		}
	}))
	defer up.Close()
	p := proxy(onlyFirstLoopback)
	for path, want := range map[string]int{"/script": 415, "/big-image": 413, "/image": 200, "/vtt": 200} {
		if w := get(p, up.URL+path, nil); w.Code != want {
			t.Errorf("%s: %d, want %d", path, w.Code, want)
		}
	}
}

func TestGetOnlyAndARateLimit(t *testing.T) {
	p := proxy(onlyFirstLoopback)
	w := httptest.NewRecorder()
	p.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/fetch?url=http%3A%2F%2Fx", nil))
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST: %d", w.Code)
	}
	p.Limiter = NewLimiter(2, time.Minute)
	codes := []int{}
	for i := 0; i < 3; i++ {
		codes = append(codes, get(p, "", nil).Code)
	}
	if fmt.Sprint(codes) != "[400 400 429]" {
		t.Fatalf("codes %v", codes)
	}
}
