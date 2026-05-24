import '@testing-library/jest-dom'

// Mock window.matchMedia for Ant Design responsive components (Grid, useBreakpoint, etc.)
Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {
        },
        removeListener: () => {
        },
        addEventListener: () => {
        },
        removeEventListener: () => {
        },
        dispatchEvent: () => false,
    }),
})

// Mock ResizeObserver for Ant Design components that use it
globalThis.ResizeObserver = class ResizeObserver {
    observe() {
    }

    unobserve() {
    }

    disconnect() {
    }
}

// Mock scrollIntoView for jsdom
Element.prototype.scrollIntoView = () => {
}
