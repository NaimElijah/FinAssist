"""Anti-corruption layer over the external price feed.

The vendor's field names, units and timestamp conventions stop here and become
marketdata.domain.model types. This is what lets the feed be replaced without the rest of the
context noticing - which matters more than usual, since the feed is not chosen yet.
"""
