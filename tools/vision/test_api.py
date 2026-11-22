import base64, concurrent.futures, io, json, threading, time, unittest, urllib.error, urllib.request
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from PIL import Image
from vision_api import Handler, VisionError

class ApiTests(unittest.TestCase):
 def setUp(self):
  Handler.cache.clear();Handler.stream_frames.clear();Handler.next_allowed_at=0
  Handler.verified_model=None;Handler.successful_requests=0
  self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
  self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
  self.url=f'http://127.0.0.1:{self.server.server_port}'
  out=io.BytesIO();Image.new('RGB',(64,48),'red').save(out,format='JPEG')
  self.body={'sessionId':'s','streamId':'g','frameId':1,'imageBase64':base64.b64encode(out.getvalue()).decode()}
 def tearDown(self):
  self.server.shutdown();self.server.server_close();self.thread.join(timeout=2)
 def request(self,body=None,path='/v1/observations'):
  data=json.dumps(self.body if body is None else body).encode()
  try:
   with urllib.request.urlopen(urllib.request.Request(self.url+path,data=data,headers={'Content-Type':'application/json'}),timeout=3) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:
   with e:return e.code,json.loads(e.read())
 def result(self):
  return {'engine':'GEMINI/gemini-3.1-flash-lite','status':'SUCCESS','sourceAgeMs':50,'detections':[],**{k:self.body[k] for k in ('sessionId','streamId','frameId')}}
 def test_same_frame_is_cached_without_second_provider_call(self):
  with patch('vision_api.infer',return_value=self.result()) as backend:
   self.assertEqual(200,self.request()[0]);self.assertEqual(200,self.request()[0]);self.assertEqual(1,backend.call_count)
 def test_same_id_different_bytes_conflicts(self):
  with patch('vision_api.infer',return_value=self.result()):
   self.request();changed={**self.body,'imageBase64':base64.b64encode(b'different').decode()}
   self.assertEqual((409,'FRAME_ID_CONFLICT'),(lambda r:(r[0],r[1]['status']))(self.request(changed)))
 def test_request_id_tracks_each_caller_without_repeating_inference(self):
  with patch('vision_api.infer',return_value=self.result()) as backend:
   first={**self.body,'requestId':'scan-one'}
   self.assertEqual('scan-one',self.request(first)[1]['requestId'])
   self.assertEqual('scan-two',self.request({**first,'requestId':'scan-two'})[1]['requestId'])
   self.assertNotIn('requestId',self.request()[1])
   self.assertEqual(1,backend.call_count)
   self.assertEqual(4,len(backend.call_args.args)) # Correlation is not sent to the model.
 def test_invalid_request_id_does_not_reach_provider(self):
  with patch('vision_api.infer') as backend:
   for request_id in ['', 'x'*129, 3, True, {}]:
    self.assertEqual(400,self.request({**self.body,'requestId':request_id})[0])
   backend.assert_not_called()
 def test_failed_analysis_retains_optional_request_id(self):
  with patch('vision_api.infer',side_effect=VisionError('NETWORK_UNAVAILABLE',503)):
   self.assertEqual('scan-one',self.request({**self.body,'requestId':'scan-one'})[1]['requestId'])
 def test_expired_replay_not_run_again(self):
  with patch('vision_api.infer',return_value=self.result()) as backend:
   self.request();k=('s','g',1);digest,result,completed=Handler.cache[k];Handler.cache[k]=(digest,result,completed-20)
   self.assertEqual(410,self.request()[0]);self.assertEqual(1,backend.call_count)
 def test_out_of_order_frame_rejected(self):
  Handler.stream_frames[('s','g')]=9
  with patch('vision_api.infer') as backend:
   self.assertEqual(409,self.request()[0]);backend.assert_not_called()
 def test_failure_retains_request_identity_and_is_not_empty_success(self):
  with patch('vision_api.infer',side_effect=VisionError('NETWORK_UNAVAILABLE',503)):
   status,result=self.request();self.assertEqual(503,status);self.assertEqual('NETWORK_UNAVAILABLE',result['status']);self.assertEqual(1,result['frameId'])
 def test_only_one_inference_is_in_flight(self):
  entered=threading.Event();release=threading.Event()
  def slow(*args):entered.set();release.wait(timeout=2);return self.result()
  with patch('vision_api.infer',side_effect=slow) as backend,concurrent.futures.ThreadPoolExecutor(1) as pool:
   future=pool.submit(self.request);self.assertTrue(entered.wait(timeout=2))
   try:self.assertEqual(429,self.request()[0])
   finally:release.set()
   self.assertEqual(200,future.result()[0]);self.assertEqual(1,backend.call_count)
 def test_quota_starts_cooldown_without_blind_retry(self):
  with patch('vision_api.infer',side_effect=VisionError('QUOTA_OR_RATE_LIMIT',429)) as backend:
   self.assertEqual(429,self.request()[0]);self.assertEqual('PROVIDER_COOLDOWN',self.request()[1]['status']);self.assertEqual(1,backend.call_count)
 def test_malformed_request_does_not_reach_provider(self):
  with patch('vision_api.infer') as backend:
   self.assertEqual(400,self.request({'frameId':0})[0]);backend.assert_not_called()

if __name__=='__main__':unittest.main()
