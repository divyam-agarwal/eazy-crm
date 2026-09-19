import { setupServer } from 'msw/node';

/** The one MSW server for every component test. Handlers are added per test with server.use(). */
export const server = setupServer();
