// Never executed. If key typing silently degraded to `string`, the expect-error directive below would be
// unused and `tsc` would fail — so this file is the red fixture for "unknown key fails tsc".
import i18next from 'i18next';

i18next.t('actions.retry');
i18next.t('login.heading', { ns: 'auth' });
// @ts-expect-error — not a key in common.json
i18next.t('actions.doesNotExist');
