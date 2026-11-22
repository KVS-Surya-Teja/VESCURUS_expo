"""Offline consequential contract checks; no model calls or API key needed."""
import base64,io,json,unittest
from unittest.mock import patch
from PIL import Image
from vision_api import normalize_label, parse_response, prepare_image, VisionError, infer

class VisionContractTests(unittest.TestCase):
 def test_exact_label_normalization(self):
  for raw,expected in [('eggplant','Eggplant'),('penne','Penne'),('jalapeno','Jalapeno'),('eggs','Egg'),('phone','Unsupported object')]:
   self.assertEqual(expected,normalize_label(raw))
 def test_empty_success(self):
  self.assertEqual([],parse_response('{"detections":[],"overall_confidence":0}'))
 def test_not_fabricating_empty_success(self):
  for text in ['{}','not JSON','{"detections":[],"overall_confidence":3}']:
   with self.assertRaises(VisionError):parse_response(text)
 def test_preserves_supported_flag_and_coordinates(self):
  result=parse_response('{"detections":[{"label":"eggplant","confidence":0.8,"box_2d":[100,200,700,900],"is_supported":false}],"overall_confidence":0.8}')
  self.assertFalse(result[0]['supported']);self.assertEqual(.2,result[0]['box_2d']['left'])
 def test_rejects_invalid_boxes(self):
  for box in [[0,0,0,0],[700,500,100,900],[-1,0,100,100],[1,2,3],[0,0,1001,1000]]:
   data={'detections':[{'label':'tomato','confidence':.8,'box_2d':box,'is_supported':True}],'overall_confidence':.8}
   with self.assertRaises(VisionError):parse_response(json.dumps(data))
 def test_rejects_nan_confidence(self):
  with self.assertRaises(VisionError):parse_response('{"detections":[],"overall_confidence":NaN}')
 def test_rejects_invalid_image(self):
  with self.assertRaises(VisionError):prepare_image(b'not a photo')
 def test_actual_resize_dimensions(self):
  output=io.BytesIO();Image.new('RGB',(1200,900),'red').save(output,format='JPEG')
  jpeg,size=prepare_image(output.getvalue());self.assertEqual((640,480),size)
  self.assertEqual(size,Image.open(io.BytesIO(jpeg)).size)
 def test_credentials_failure_is_explicit(self):
  output=io.BytesIO();Image.new('RGB',(100,100),'red').save(output,format='JPEG')
  with patch('vision_api.configuration',return_value=('','gemini-3.1-flash-lite')):
   with self.assertRaises(VisionError) as error:infer(output.getvalue(),'s','g',1)
   self.assertEqual('UNCONFIGURED',error.exception.status)

if __name__=='__main__':unittest.main()
