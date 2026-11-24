import base64
import json
import threading
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import bridge

JPEG = base64.b64encode(b'\xff\xd8\xff' + b'X' * 150 + b'\xff\xd9').decode()
DRAFT = {'product_name': 'Fictional example', 'strength': '2.5 mg', 'directions': 'Take 1 tablet daily.'}
SETTINGS = {'OPENAI_API_KEY': 'test-only-key', 'OPENAI_MODEL': 'test-vision-model', 'BRIDGE_TOKEN': 'x'*40, 'RETENTION_POLICY': 'standard'}


class Contracts(unittest.TestCase):
    def test_all_images_are_in_one_request_and_not_stored(self):
        body = bridge.openai_payload([JPEG, JPEG], 'test-vision-model')
        self.assertFalse(body['store']); self.assertFalse(body['background'])
        self.assertEqual(1, len(body['input']))
        self.assertEqual(2, sum(p['type']=='input_image' for p in body['input'][0]['content']))
        self.assertTrue(body['text']['format']['strict'])
        self.assertNotIn('tools', body); self.assertNotIn('previous_response_id', body)
        self.assertNotIn('2.5', body['instructions'])

    def test_upload_requires_consent_and_bounded_jpeg_inputs(self):
        self.assertEqual([JPEG, JPEG], bridge.validate_upload({'consent': True, 'images': [JPEG,JPEG]}))
        for value in [{'consent':False,'images':[JPEG,JPEG]}, {'consent':True,'images':[JPEG]},
                      {'consent':True,'images':[JPEG]*5}, {'consent':True,'images':['not-base64',JPEG]},
                      {'consent':True,'images':[JPEG,JPEG],'target_strength':'2.5 mg'}]:
            with self.assertRaises(bridge.BridgeError): bridge.validate_upload(value)

    def test_rejects_extra_numeric_duplicate_and_truncated_results(self):
        self.assertEqual(DRAFT, bridge.validated_draft(DRAFT))
        for value in [dict(DRAFT,strength=2.5),dict(DRAFT,patient='name'),{},dict(DRAFT,directions='x'*2001)]:
            with self.assertRaises(bridge.BridgeError): bridge.validated_draft(value)
        with self.assertRaises(ValueError): bridge.parse_json('{"strength":"2.5 mg","strength":"25 mg"}')
        with self.assertRaises(bridge.BridgeError): bridge.read_openai_result({'status':'incomplete','output':[]})
        with self.assertRaises(bridge.BridgeError): bridge.read_openai_result({'status':'completed','output':[{'type':'message','content':[{'type':'refusal'}]}]})
        complete={'status':'completed','output':[{'type':'message','content':[{'type':'output_text','text':json.dumps(DRAFT)}]}]}
        self.assertEqual(DRAFT,bridge.read_openai_result(complete))


class HTTPContracts(unittest.TestCase):
    def setUp(self):
        self.configuration = patch.object(bridge, 'config', return_value=SETTINGS.copy()); self.configuration.start()
        self.provider = patch.object(bridge,'call_openai',return_value=DRAFT); self.call = self.provider.start()
        self.server = bridge.Server(('127.0.0.1',0),bridge.Handler); self.server.last_request=0
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base='http://127.0.0.1:'+str(self.server.server_port)
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.provider.stop();self.configuration.stop()
    def request(self,path,body=None,token=SETTINGS['BRIDGE_TOKEN']):
        data=None if body is None else json.dumps(body).encode()
        req=Request(self.base+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        try:
            with urlopen(req,timeout=2) as response:return response.status,json.load(response),response.headers
        except HTTPError as error:
            with error:return error.code,json.load(error),error.headers
    def test_setup_check_never_sends_photos(self):
        status,data,headers=self.request('/v1/config')
        self.assertEqual(200,status);self.assertTrue(data['ready']);self.assertEqual('no-store',headers['Cache-Control']);self.call.assert_not_called()
    def test_auth_failure_cannot_trigger_paid_inference(self):
        status,_,_=self.request('/v1/extract',{'consent':True,'images':[JPEG,JPEG]},token='wrong')
        self.assertEqual(401,status);self.call.assert_not_called()
    def test_missing_consent_cannot_trigger_paid_inference(self):
        status,_,_=self.request('/v1/extract',{'consent':False,'images':[JPEG,JPEG]})
        self.assertEqual(400,status);self.call.assert_not_called()
    def test_one_explicit_upload_produces_one_provider_call(self):
        status,data,_=self.request('/v1/extract',{'consent':True,'images':[JPEG,JPEG]})
        self.assertEqual(200,status);self.assertEqual(DRAFT,data);self.call.assert_called_once_with([JPEG,JPEG],SETTINGS)
    def test_unconfigured_account_blocks_upload(self):
        self.configuration.stop();self.configuration=patch.object(bridge,'config',return_value=dict(SETTINGS,OPENAI_API_KEY=''));self.configuration.start()
        status,data,_=self.request('/v1/config');self.assertFalse(data['ready'])
        status,_,_=self.request('/v1/extract',{'consent':True,'images':[JPEG,JPEG]})
        self.assertEqual(503,status);self.call.assert_not_called()

if __name__=='__main__':unittest.main()
