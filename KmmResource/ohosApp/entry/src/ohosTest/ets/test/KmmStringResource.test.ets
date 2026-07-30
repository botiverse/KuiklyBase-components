import { resourceManager } from '@kit.LocalizationKit';
import { abilityDelegatorRegistry } from '@kit.TestKit';
import { describe, expect, it } from '@ohos/hypium';

function testResourceManager(): resourceManager.ResourceManager {
  const argumentsInfo = abilityDelegatorRegistry.getArguments();
  const moduleName = argumentsInfo.parameters['-m'];
  return abilityDelegatorRegistry
    .getAbilityDelegator()
    .getAppContext()
    .getApplicationContext()
    .createModuleContext(moduleName)
    .resourceManager;
}

export default function kmmStringResourceTest() {
  describe('KmmStringResourceContract', () => {
    it('zeroArgumentLookupReturnsStableTemplate', 0, () => {
      const value = testResourceManager().getStringByNameSync('kmm_raw_positional_template');
      expect(value).assertEqual('Failed to save tab order: %1$s');
    });

    it('formattedLookupSubstitutesPositionalArgument', 0, () => {
      const value = testResourceManager().getStringByNameSync(
        'kmm_raw_positional_template',
        'disk full'
      );
      expect(value).assertEqual('Failed to save tab order: disk full');
    });

    it('ordinaryLookupDoesNotRegress', 0, () => {
      const value = testResourceManager().getStringByNameSync('kmm_ordinary_string');
      expect(value).assertEqual('Status OK');
    });

    it('multiArgumentLookupPreservesEscapedPercent', 0, () => {
      const value = testResourceManager().getStringByNameSync(
        'kmm_multi_and_escaped_percent',
        'alpha',
        7
      );
      expect(value).assertEqual('Pair alpha/7/100%');
    });
  });
}
