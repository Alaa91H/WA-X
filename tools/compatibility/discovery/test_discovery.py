"""Fixture-backed tests for metadata-only target-version discovery.

Run with: python3 tools/compatibility/discovery/test_discovery.py
"""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from discovery.models import (  # noqa: E402
    Channel, DiscoveryState, ObservationDelta, TargetObservation,
    compare_observations, is_stale,
)
from discovery.sources import (  # noqa: E402
    DiscoveryError, HttpResponse, SOURCES, discover,
)


def fixture(name: str) -> bytes:
    return (HERE / "fixtures" / name).read_bytes()


class MockTransport:
    def __init__(self, result: HttpResponse | None = None, error: Exception | None = None):
        self.result = result
        self.error = error
        self.calls: list[tuple[str, int, int]] = []

    def get(self, url: str, *, timeout_seconds: int, max_bytes: int) -> HttpResponse:
        self.calls.append((url, timeout_seconds, max_bytes))
        if self.error:
            raise self.error
        if self.result is None:
            raise AssertionError("test transport has no response")
        return self.result


def html_response(target: str, body: bytes, final_url: str | None = None) -> HttpResponse:
    return HttpResponse(
        final_url or SOURCES[target].url, 200, "text/html", body
    )


class DiscoveryTests(unittest.TestCase):
    observed_at = datetime(2026, 10, 8, 12, 0, tzinfo=timezone.utc)

    def test_messenger_exact_label_has_no_version_code(self):
        transport = MockTransport(html_response("whatsapp", fixture("whatsapp-current.html")))
        result = discover("whatsapp", transport=transport, observed_at=self.observed_at)
        self.assertEqual(result.package_id, "com.whatsapp")
        self.assertEqual(result.channel, Channel.STABLE)
        self.assertEqual(result.state, DiscoveryState.LATEST_FROM_SOURCE)
        self.assertEqual(result.version_name, "2.99.10.42")
        self.assertIsNone(result.version_code)
        self.assertEqual(result.confidence, "exact_source_claim")
        self.assertEqual(transport.calls[0][1:], (10, 1_000_000))

    def test_business_updated_date_is_not_an_app_version(self):
        result = discover(
            "business",
            transport=MockTransport(html_response("business", fixture("business-metadata-only.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(result.package_id, "com.whatsapp.w4b")
        self.assertEqual(result.channel, Channel.UNKNOWN)
        self.assertEqual(result.state, DiscoveryState.UNKNOWN)
        self.assertIsNone(result.version_name)
        self.assertEqual(result.source_updated_on, "Oct 8, 2026")
        self.assertEqual(result.confidence, "metadata_only")

    def test_script_only_or_missing_version_is_unknown(self):
        result = discover(
            "whatsapp",
            transport=MockTransport(html_response("whatsapp", fixture("script-only-version.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(result.state, DiscoveryState.UNKNOWN)
        self.assertIsNone(result.version_name)

    def test_multiple_version_labels_are_ambiguous(self):
        result = discover(
            "whatsapp",
            transport=MockTransport(html_response("whatsapp", fixture("whatsapp-ambiguous.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(result.state, DiscoveryState.UNKNOWN)
        self.assertIsNone(result.version_name)
        self.assertIn("multiple", result.detail)

    def test_malformed_or_non_html_response_cannot_claim_a_version(self):
        malformed = discover(
            "whatsapp",
            transport=MockTransport(html_response("whatsapp", fixture("malformed.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(malformed.state, DiscoveryState.UNKNOWN)
        self.assertIsNone(malformed.version_name)
        non_html = discover(
            "whatsapp",
            transport=MockTransport(HttpResponse(
                SOURCES["whatsapp"].url, 200, "application/octet-stream", b"Version 2.99.1"
            )),
            observed_at=self.observed_at,
        )
        self.assertEqual(non_html.state, DiscoveryState.DISCOVERY_UNAVAILABLE)

    def test_source_failure_is_not_reported_unchanged(self):
        result = discover(
            "business", transport=MockTransport(error=DiscoveryError("offline")),
            observed_at=self.observed_at,
        )
        self.assertEqual(result.state, DiscoveryState.DISCOVERY_UNAVAILABLE)
        self.assertIsNone(result.version_name)

    def test_disallowed_redirect_target_is_rejected(self):
        result = discover(
            "whatsapp",
            transport=MockTransport(html_response(
                "whatsapp", fixture("whatsapp-current.html"),
                "https://attacker.example/download",
            )),
            observed_at=self.observed_at,
        )
        self.assertEqual(result.state, DiscoveryState.DISCOVERY_UNAVAILABLE)
        self.assertIn("allowlist", result.detail)

    def test_oversized_source_response_is_rejected(self):
        result = discover(
            "whatsapp", transport=MockTransport(html_response("whatsapp", b"x" * 32)),
            observed_at=self.observed_at, max_bytes=16,
        )
        self.assertEqual(result.state, DiscoveryState.DISCOVERY_UNAVAILABLE)
        self.assertIn("size limit", result.detail)

    def test_version_comparison_and_unknown_are_truthful(self):
        previous = discover(
            "whatsapp", transport=MockTransport(html_response("whatsapp", b"<p>Version 2.98.1</p>")),
            observed_at=self.observed_at,
        )
        current = discover(
            "whatsapp", transport=MockTransport(html_response("whatsapp", fixture("whatsapp-current.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(compare_observations(None, previous), ObservationDelta.NEW_VERSION)
        self.assertEqual(compare_observations(previous, current), ObservationDelta.VERSION_CHANGED)
        self.assertEqual(compare_observations(previous, previous), ObservationDelta.UNCHANGED)
        unknown = discover(
            "business", transport=MockTransport(html_response("business", fixture("business-metadata-only.html"))),
            observed_at=self.observed_at,
        )
        self.assertEqual(compare_observations(previous, unknown), ObservationDelta.UNKNOWN)

    def test_freshness_marks_expired_and_future_observations_stale(self):
        result = discover(
            "whatsapp", transport=MockTransport(html_response("whatsapp", fixture("whatsapp-current.html"))),
            observed_at=self.observed_at,
        )
        self.assertFalse(is_stale(result, now=self.observed_at + timedelta(hours=1)))
        self.assertTrue(is_stale(result, now=self.observed_at + timedelta(days=2)))
        self.assertTrue(is_stale(result, now=self.observed_at - timedelta(seconds=1)))

    def test_model_rejects_version_code_and_timezone_naive_time(self):
        fields = dict(
            package_id="com.whatsapp", source_id="fixture",
            source_url="https://www.whatsapp.com/download/android",
            channel=Channel.STABLE, state=DiscoveryState.LATEST_FROM_SOURCE,
            observed_at="2026-10-08T12:00:00Z", version_name="2.99.1",
        )
        with self.assertRaisesRegex(ValueError, "version_code"):
            TargetObservation(**fields, version_code=29901)
        with self.assertRaisesRegex(ValueError, "timezone"):
            TargetObservation(**{**fields, "observed_at": "2026-10-08T12:00:00"})

    def test_unknown_target_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "target must be"):
            discover("mirror")


if __name__ == "__main__":
    unittest.main()
