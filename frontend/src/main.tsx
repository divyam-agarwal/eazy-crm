import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { startApp } from '@/app/bootstrap';
import { Providers } from '@/app/providers';
import './index.css';

const { queryClient, router } = startApp();
const root = document.getElementById('root');
if (!root) throw new Error('#root is missing from index.html');
createRoot(root).render(
  <StrictMode>
    <Providers router={router} queryClient={queryClient} />
  </StrictMode>,
);
