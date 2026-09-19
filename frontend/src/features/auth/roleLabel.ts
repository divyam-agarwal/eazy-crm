import { useTranslation } from 'react-i18next';
import { isRole } from './session/toMe';

export function useRoleLabel(): (role: string) => string {
  const { t } = useTranslation('common');
  return (role) => (isRole(role) ? t(`roles.${role}`) : role);
}
