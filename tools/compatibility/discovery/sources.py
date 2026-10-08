"""Read-only adapters for official listing metadata; APK acquisition is out of scope."""

from __future__ import annotations

import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from html.parser import HTMLParser
from typing import Protocol
from urllib.parse import urlparse

from .models import Channel, DiscoveryState, TargetObservation, utc_timestamp


@dataclass(frozen=True)
class Source:
    target: str
    package_id: str
    source_id: str
    url: str
    allowed_hosts: tuple[str, ...]
    channel: Channel


SOURCES = {
    "whatsapp": Source(
        "whatsapp", "com.whatsapp", "whatsapp_official_android_download",
        "https://www.whatsapp.com/download/android", ("www.whatsapp.com",), Channel.STABLE,
    ),
    "business": Source(
        "business", "com.whatsapp.w4b", "google_play_whatsapp_business_listing",
        "https://play.google.com/store/apps/details?id=com.whatsapp.w4b&hl=en",
        ("play.google.com",), Channel.UNKNOWN,
    ),
}

MAX_RESPONSE_BYTES = 1_000_000
DEFAULT_TIMEOUT_SECONDS = 10
_VERSION_PATTERN = re.compile(
    r"\b(?:current\s+)?version\s*[:#]?\s*\(?\s*"
    r"([0-9]+(?:\.[0-9A-Za-z]+){2,3})\s*\)?", re.IGNORECASE
)
_UPDATED_PATTERN = re.compile(
    r"\bUpdated on\s+([A-Za-z]{3,9}\s+\d{1,2},\s+\d{4})", re.IGNORECASE
)


@dataclass(frozen=True)
class HttpResponse:
    final_url: str
    status: int
    content_type: str
    body: bytes


class Transport(Protocol):
    def get(self, url: str, *, timeout_seconds: int, max_bytes: int) -> HttpResponse: ...


class DiscoveryError(Exception):
    """A source could not be fetched or safely interpreted."""


def _validate_https_url(url: str, allowed_hosts: tuple[str, ...]) -> None:
    try:
        parsed = urlparse(url)
        port = parsed.port
    except ValueError as error:
        raise DiscoveryError("source URL is malformed") from error
    if parsed.scheme != "https" or parsed.hostname not in allowed_hosts:
        raise DiscoveryError("source URL or redirect is outside its HTTPS host allowlist")
    if parsed.username or parsed.password or port not in (None, 443):
        raise DiscoveryError("source URL contains credentials or a non-standard port")


class _AllowlistedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, allowed_hosts: tuple[str, ...], max_redirects: int) -> None:
        super().__init__()
        self.allowed_hosts = allowed_hosts
        self.max_redirects = max_redirects
        self.redirect_count = 0

    def redirect_request(self, request, response, code, message, headers, new_url):
        self.redirect_count += 1
        if self.redirect_count > self.max_redirects:
            raise urllib.error.URLError("redirect limit exceeded")
        _validate_https_url(new_url, self.allowed_hosts)
        return super().redirect_request(request, response, code, message, headers, new_url)


class UrllibTransport:
    """Bounded HTTPS transport with a strict per-source redirect allowlist."""

    def __init__(self, source: Source, *, max_redirects: int = 3) -> None:
        self.source = source
        self.max_redirects = max_redirects

    def get(self, url: str, *, timeout_seconds: int, max_bytes: int) -> HttpResponse:
        _validate_https_url(url, self.source.allowed_hosts)
        opener = urllib.request.build_opener(
            _AllowlistedRedirectHandler(self.source.allowed_hosts, self.max_redirects)
        )
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "WA-X-Version-Discovery/1.0 (+read-only metadata)"},
        )
        try:
            with opener.open(request, timeout=timeout_seconds) as response:
                body = response.read(max_bytes + 1)
                return HttpResponse(
                    response.geturl(), response.status,
                    response.headers.get_content_type(), body,
                )
        except (OSError, urllib.error.URLError, TimeoutError) as error:
            raise DiscoveryError("official source request failed") from error


class _VisibleTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._suppressed = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() in ("script", "style", "noscript"):
            self._suppressed += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in ("script", "style", "noscript") and self._suppressed:
            self._suppressed -= 1

    def handle_data(self, data: str) -> None:
        if not self._suppressed:
            self.parts.append(data)


def _visible_text(body: bytes) -> str:
    parser = _VisibleTextParser()
    parser.feed(body.decode("utf-8", errors="replace"))
    parser.close()
    return " ".join(" ".join(parser.parts).split())


def _observation(
    source: Source, *, observed_at: datetime | None, state: DiscoveryState,
    detail: str, version_name: str | None = None,
    source_updated_on: str | None = None, confidence: str = "unknown",
) -> TargetObservation:
    return TargetObservation(
        package_id=source.package_id, source_id=source.source_id,
        source_url=source.url, channel=source.channel, state=state,
        observed_at=utc_timestamp(observed_at), version_name=version_name,
        source_updated_on=source_updated_on, confidence=confidence, detail=detail,
    )


def discover(
    target: str, *, transport: Transport | None = None,
    observed_at: datetime | None = None,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    max_bytes: int = MAX_RESPONSE_BYTES,
) -> TargetObservation:
    """Observe an exact version only when explicitly labeled on the official page."""
    if target not in SOURCES:
        raise ValueError("target must be one of: " + ", ".join(sorted(SOURCES)))
    if timeout_seconds <= 0 or max_bytes <= 0:
        raise ValueError("timeout_seconds and max_bytes must be positive")
    source = SOURCES[target]
    try:
        _validate_https_url(source.url, source.allowed_hosts)
        response = (transport or UrllibTransport(source)).get(
            source.url, timeout_seconds=timeout_seconds, max_bytes=max_bytes
        )
        _validate_https_url(response.final_url, source.allowed_hosts)
        if response.status != 200:
            raise DiscoveryError("official source returned a non-success status")
        if len(response.body) > max_bytes:
            raise DiscoveryError("official source response exceeded the size limit")
        if response.content_type not in ("text/html", "application/xhtml+xml"):
            raise DiscoveryError("official source did not return HTML metadata")
        visible = _visible_text(response.body)
        versions = set(_VERSION_PATTERN.findall(visible))
        updated = _UPDATED_PATTERN.search(visible)
        updated_on = updated.group(1).strip() if updated else None
        if len(versions) == 1:
            version = next(iter(versions))
            return _observation(
                source, observed_at=observed_at,
                state=DiscoveryState.LATEST_FROM_SOURCE,
                version_name=version, source_updated_on=updated_on,
                confidence="exact_source_claim",
                detail="Exact version label was visible on the configured official listing.",
            )
        detail = (
            "The official listing exposed multiple different version labels."
            if versions else "The official listing did not expose an exact version label."
        )
        return _observation(
            source, observed_at=observed_at, state=DiscoveryState.UNKNOWN,
            source_updated_on=updated_on,
            confidence="metadata_only" if updated_on else "unknown", detail=detail,
        )
    except DiscoveryError as error:
        return _observation(
            source, observed_at=observed_at,
            state=DiscoveryState.DISCOVERY_UNAVAILABLE, detail=str(error),
        )


def discover_many(
    targets: tuple[str, ...] = ("whatsapp", "business"), *,
    transport_factory=None, observed_at: datetime | None = None,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    max_bytes: int = MAX_RESPONSE_BYTES,
) -> list[TargetObservation]:
    """Inspect targets independently so one unavailable source cannot hide another."""
    results = []
    for target in targets:
        source = SOURCES.get(target)
        transport = transport_factory(source) if transport_factory and source else None
        results.append(discover(
            target, transport=transport, observed_at=observed_at,
            timeout_seconds=timeout_seconds, max_bytes=max_bytes,
        ))
    return results


