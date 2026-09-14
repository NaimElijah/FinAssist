/**
 * Portfolio feature - holdings, positions and performance.
 *
 * Mirrors the `portfolio` bounded context in the Python assistant.
 */
import type { RouteObject } from 'react-router';

import { PortfolioPage } from './components/PortfolioPage';

/**
 * The portfolio area's routes, under the base path `portfolio`. src/app mounts them inside the
 * shared layout. This is the route contract; the page behind it is replaced as screens are built.
 */
export const portfolioRoutes: RouteObject[] = [{ path: 'portfolio', Component: PortfolioPage }];
