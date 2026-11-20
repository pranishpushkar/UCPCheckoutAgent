package dev.ucplab.core.merchant;

/**
 * Who finishes a purchase (19, Appendix B): the agent sends {@code complete}, or the buyer finishes on the merchant's
 * page after a handoff (7.2, H5), or the buyer is given a catalog seller's own link and nothing is sent to the seller
 * (7.2 link rows, 26.7).
 */
public enum Completion { AGENT, HANDOFF, LINK }
