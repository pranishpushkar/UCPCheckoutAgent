package dev.ucplab.core.merchant;

/**
 * How the agent reaches a merchant's checkout (19, Appendix B): UCP over REST, or over MCP (H5); or a catalog source,
 * Shopify's Global Catalog, read only and with no checkout at all (26.7).
 */
public enum Transport { REST, MCP, CATALOG }
