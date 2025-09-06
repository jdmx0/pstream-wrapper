/**
 * TV Cursor Helper - Snap-to-clickable functionality for Android TV
 * Provides JavaScript interface for finding nearest clickable elements.
 */

(function() {
    'use strict';

    // TV Cursor namespace
    window.TVCursor = window.TVCursor || {};

    /**
     * Finds the nearest clickable element to the given coordinates.
     * @param {number} cursorX - Cursor X position in viewport coordinates
     * @param {number} cursorY - Cursor Y position in viewport coordinates
     * @returns {Object|null} - Nearest clickable element info or null
     */
    TVCursor.nearestClickable = function(cursorX, cursorY) {
        const elements = document.querySelectorAll(
            'a, button, input, textarea, select, [role="button"], [tabindex]:not([tabindex="-1"])'
        );

        let nearest = null;
        let minDistance = Infinity;

        elements.forEach(function(el) {
            // Check if element is visible
            const rect = el.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return;

            const style = window.getComputedStyle(el);
            if (style.display === 'none' ||
                style.visibility === 'hidden' ||
                style.opacity === '0') return;

            // Check if element has valid position
            if (!el.offsetParent && el !== document.body) return;

            // Calculate center point
            const centerX = rect.left + rect.width / 2;
            const centerY = rect.top + rect.height / 2;

            // Calculate distance from cursor
            const distance = Math.sqrt(
                Math.pow(centerX - cursorX, 2) +
                Math.pow(centerY - cursorY, 2)
            );

            if (distance < minDistance) {
                minDistance = distance;
                nearest = {
                    x: centerX,
                    y: centerY,
                    element: el,
                    rect: rect
                };
            }
        });

        return nearest;
    };

    /**
     * Gets clickable elements within a radius for debugging.
     * @param {number} cursorX - Cursor X position
     * @param {number} cursorY - Cursor Y position
     * @param {number} radius - Search radius in pixels
     * @returns {Array} - Array of clickable elements within radius
     */
    TVCursor.clickablesInRadius = function(cursorX, cursorY, radius) {
        const elements = document.querySelectorAll(
            'a, button, input, textarea, select, [role="button"], [tabindex]:not([tabindex="-1"])'
        );

        const result = [];

        elements.forEach(function(el) {
            const rect = el.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return;

            const style = window.getComputedStyle(el);
            if (style.display === 'none' ||
                style.visibility === 'hidden' ||
                style.opacity === '0') return;

            if (!el.offsetParent && el !== document.body) return;

            const centerX = rect.left + rect.width / 2;
            const centerY = rect.top + rect.height / 2;

            const distance = Math.sqrt(
                Math.pow(centerX - cursorX, 2) +
                Math.pow(centerY - cursorY, 2)
            );

            if (distance <= radius) {
                result.push({
                    x: centerX,
                    y: centerY,
                    element: el,
                    rect: rect,
                    distance: distance
                });
            }
        });

        return result.sort(function(a, b) {
            return a.distance - b.distance;
        });
    };

    /**
     * Checks if an element is currently clickable/visible.
     * @param {Element} element - Element to check
     * @returns {boolean} - Whether element is clickable
     */
    TVCursor.isClickable = function(element) {
        if (!element) return false;

        const rect = element.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return false;

        const style = window.getComputedStyle(element);
        if (style.display === 'none' ||
            style.visibility === 'hidden' ||
            style.opacity === '0') return false;

        if (style.pointerEvents === 'none') return false;

        if (!element.offsetParent && element !== document.body) return false;

        return true;
    };

    /**
     * Gets the viewport coordinates for a given screen position,
     * accounting for scroll and zoom.
     * @param {number} screenX - Screen X coordinate
     * @param {number} screenY - Screen Y coordinate
     * @returns {Object} - Viewport coordinates
     */
    TVCursor.screenToViewport = function(screenX, screenY) {
        return {
            x: screenX - window.scrollX,
            y: screenY - window.scrollY
        };
    };

    /**
     * Gets the screen coordinates for a given viewport position.
     * @param {number} viewportX - Viewport X coordinate
     * @param {number} viewportY - Viewport Y coordinate
     * @returns {Object} - Screen coordinates
     */
    TVCursor.viewportToScreen = function(viewportX, viewportY) {
        return {
            x: viewportX + window.scrollX,
            y: viewportY + window.scrollY
        };
    };

    // Expose the API
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = TVCursor;
    }

})();
