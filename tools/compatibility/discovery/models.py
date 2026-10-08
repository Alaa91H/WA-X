"""Version observations are metadata claims, never compatibility verdicts."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from enum import Enum
from typing import Any


class Channel(str, Enum):
    STABLE = "stable"
    BETA = "beta"
    UNKNOWN = "unknown"


class DiscoveryState(str, Enum):
    LATEST_FROM_SOURCE = "LATEST_FROM_SOURCE"
    DISCOVERY_UNAVAILABLE = "DISCOVERY_UNAVAILABLE"
    UNKNOWN = "UNKNOWN"


class ObservationDelta(str, Enum):
    NEW_VERSION = "NEW_VERSION"
    VERSION_CHANGED = "VERSION_CHANGED"
    UNCHANGED = "UNCHANGED"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class TargetObservation:
    package_id: str
    source_id: str
    source_url: str
    channel: Channel
    state: DiscoveryState
    observed_at: str
    version_name: str | None = None
    version_code: int | None = None
    source_updated_on: str | None = None
    confidence: str = "unknown"
    detail: str = ""

    def __post_init__(self) -> None:
        if not self.package_id or not self.source_id or not self.source_url:
            raise ValueError("package_id, source_id, and source_url are required")
        try:
            parsed = datetime.fromisoformat(self.observed_at.replace("Z", "+00:00"))
        except ValueError as error:
            raise ValueError("observed_at must be an ISO-8601 timestamp") from error
        if parsed.tzinfo is None:
            raise ValueError("observed_at must include a timezone")
        if self.state is DiscoveryState.LATEST_FROM_SOURCE and not self.version_name:
            raise ValueError("LATEST_FROM_SOURCE requires an exact version_name")
        if self.state is not DiscoveryState.LATEST_FROM_SOURCE and self.version_name:
            raise ValueError("a version_name requires LATEST_FROM_SOURCE state")
        if self.version_code is not None:
            raise ValueError("source discovery does not establish version_code")

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["channel"] = self.channel.value
        result["state"] = self.state.value
        return result


def utc_timestamp(value: datetime | None = None) -> str:
    current = value or datetime.now(timezone.utc)
    if current.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return current.astimezone(timezone.utc).isoformat(timespec="seconds").replace(
        "+00:00", "Z"
    )


def compare_observations(
    previous: TargetObservation | None, current: TargetObservation
) -> ObservationDelta:
    """Compare exact source version labels only; this cannot detect binary changes."""
    if current.state is not DiscoveryState.LATEST_FROM_SOURCE:
        return ObservationDelta.UNKNOWN
    if previous is None:
        return ObservationDelta.NEW_VERSION
    if previous.state is not DiscoveryState.LATEST_FROM_SOURCE:
        return ObservationDelta.UNKNOWN
    if (
        previous.package_id != current.package_id
        or previous.channel is not current.channel
        or previous.source_id != current.source_id
        or previous.source_url != current.source_url
    ):
        return ObservationDelta.UNKNOWN
    if previous.version_name == current.version_name:
        return ObservationDelta.UNCHANGED
    return ObservationDelta.VERSION_CHANGED


def is_stale(
    observation: TargetObservation,
    *,
    now: datetime | None = None,
    max_age_seconds: int = 86400,
) -> bool:
    """Treat expired, malformed, naive, and future-dated observations as stale."""
    if max_age_seconds < 0:
        raise ValueError("max_age_seconds must be non-negative")
    try:
        observed_at = datetime.fromisoformat(observation.observed_at.replace("Z", "+00:00"))
    except ValueError:
        return True
    if observed_at.tzinfo is None:
        return True
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        raise ValueError("now must include a timezone")
    age = (current.astimezone(timezone.utc) - observed_at.astimezone(timezone.utc)).total_seconds()
    return age < 0 or age > max_age_seconds
