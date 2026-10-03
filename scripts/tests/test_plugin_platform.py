import hashlib,io,json,pathlib,sys,tempfile,unittest,zipfile
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from check_plugin_platform import check_archive,check_descriptor,DeliveryError

class PluginPlatformTests(unittest.TestCase):
    def archive(self,version='3.2.8',policy='3.2.8',corrupt=False):
        files={'index.d.ts':b'public type','assets/academic-plugin/contract.schema.json':b'{}'}
        lock={'version':version,'apiVersion':3,'sha256':{n:hashlib.sha256(b).hexdigest() for n,b in files.items()}}
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            for name in ['host-api','sdk']:
                z.writestr(name+'/package.json',json.dumps({'version':version}))
                z.writestr(name+'/api-lock.json',json.dumps(lock))
            for name,value in files.items():
                z.writestr('host-api/'+name,value)
                z.writestr('sdk/'+pathlib.PurePosixPath(name).name,b'bad' if corrupt else value)
            z.writestr('cli/security-policy.mjs',f"export const SECURITY_CONTRACT='{policy}'; export const SECURITY_RULE_VERSION='rules-1';")
        return data.getvalue()
    def test_old_toolchain_is_rejected_even_with_a_correct_zip_digest(self):
        with self.assertRaises(DeliveryError):check_archive(self.archive(version='3.2.7'),'3.2.8','rules-1')
    def test_audit_rules_and_contract_must_match_the_downloaded_toolchain(self):
        self.assertEqual(check_archive(self.archive(),'3.2.8','rules-1')['version'],'3.2.8')
        with self.assertRaises(DeliveryError):check_archive(self.archive(policy='3.2.7'),'3.2.8','rules-1')
        with self.assertRaises(DeliveryError):check_archive(self.archive(),'3.2.8','rules-2')
    def test_generated_contract_corruption_is_rejected(self):
        with self.assertRaises(DeliveryError):check_archive(self.archive(corrupt=True),'3.2.8')
    def test_app_asset_drift_blocks_promotion(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=pathlib.Path(tmp)/'app/src/main/assets/academic-plugin';folder.mkdir(parents=True)
            hashes={}
            for name in ['contract.schema.json','manifest.schema.json','host-sdk.js','host-capabilities.json']:
                (folder/name).write_bytes(b'fixture');hashes['assets/academic-plugin/'+name]=hashlib.sha256(b'fixture').hexdigest()
            meta={'schemaVersion':1,'bootstrapVersion':1,'apiVersion':3,'sdkVersion':'3.2.8','contractVersion':'3.2.8','contractSha256':hashes}
            self.assertEqual(check_descriptor(meta,tmp),meta)
            (folder/'contract.schema.json').write_bytes(b'new-api')
            with self.assertRaises(DeliveryError):check_descriptor(meta,tmp)
    def test_unsupported_bootstrap_is_not_silently_accepted(self):
        with self.assertRaises(DeliveryError):check_descriptor({'schemaVersion':1,'bootstrapVersion':2})
