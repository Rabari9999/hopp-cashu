# Hopp's feed proxy (ADR 0022, 6). Standard library only; a static binary on scratch.
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod *.go ./
RUN go test -count=1 ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /proxy .

FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /proxy /proxy
USER 65534:65534
EXPOSE 8643
ENTRYPOINT ["/proxy"]
