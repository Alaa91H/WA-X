"""Read-only target-version discovery primitives for WhatsApp packages."""

from .models import (
    Channel, DiscoveryState, ObservationDelta, TargetObservation,
    compare_observations, is_stale,
)
from .sources import discover, discover_many

__all__ = [
    "Channel", "DiscoveryState", "ObservationDelta", "TargetObservation",
    "compare_observations", "discover", "discover_many", "is_stale",
]
