"""Shared Gemini image implementation and loopback-only validation API. No hosted backend.
Only image bytes plus the generic prompt/schema are sent to the provider.
"""
from __future__ import annotations
import argparse, base64, hashlib, io, json, math, os, threading, time, uuid
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import urllib.request, urllib.error
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'
MAX_BODY = 3_000_000
PREPARATIONS = frozenset(('raw', 'whole', 'peeled', 'sliced', 'diced', 'julienne', 'brunoise',
                          'chopped', 'grated', 'mashed', 'fried'))

class VisionError(Exception):
    def __init__(self, status: str, http_status: int = 502):
        super().__init__(status)
        self.status, self.http_status = status, http_status

def configuration():
    props = {}
    local = ROOT / 'local.properties'
    if local.exists():
        for line in local.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                props[key.strip()] = value.strip().strip('"')
    return (os.environ.get('GEMINI_API_KEY') or props.get('GEMINI_API_KEY', ''),
            os.environ.get('GEMINI_MODEL') or props.get('GEMINI_MODEL', 'gemini-3.1-flash-lite'))

def normalize_label(label):
    if not isinstance(label, str) or not label.strip() or len(label) > 120:
        raise VisionError('MALFORMED_RESPONSE')
    text = ' '.join(label.strip().lower().split())
    if text in {'egg', 'eggs', 'boiled egg', 'raw egg', 'peeled egg', 'egg in shell'}:
        return 'Egg'
    if text in {'unsupported', 'unsupported object', 'hazard', 'phone', 'cable', 'pen'}:
        return 'Unsupported object'
    return text.title()

def number(value, low, high):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high

def parse_response(text):
    try:
        root = json.loads(text)
        items = root['detections']
        if not isinstance(items, list) or len(items) > 100 or not number(root['overall_confidence'], 0, 1):
            raise ValueError()
        result = []
        for o in items:
            label = normalize_label(o['label'])
            b = o['box_2d']
            if (not number(o['confidence'], 0, 1) or type(o['is_supported']) is not bool or
                not isinstance(b, list) or len(b) != 4 or not all(number(v, 0, 1000) for v in b) or
                b[2] <= b[0] or b[3] <= b[1]):
                raise ValueError()
            result.append({'label': label, 'confidence': o['confidence'], 'supported': o['is_supported'],
                           'box_2d': dict(zip(('top','left','bottom','right'), [v/1000 for v in b]))})
            preparation = o.get('preparation')
            if isinstance(preparation, str) and o['is_supported']:
                preparation = preparation.strip().lower()
                if len(preparation) <= 24 and preparation in PREPARATIONS:
                    result[-1]['preparation'] = preparation
        return result
    except (ValueError, KeyError, TypeError, AttributeError):
        raise VisionError('MALFORMED_RESPONSE') from None

def prepare_image(data):
    if not data or len(data) > MAX_BODY:
        raise VisionError('INVALID_IMAGE', 400)
    try:
        with Image.open(io.BytesIO(data)) as original:
            if original.width * original.height > 24_000_000:
                raise ValueError()
            image = ImageOps.exif_transpose(original).convert('RGB')
            image.thumbnail((640, 640), Image.Resampling.LANCZOS)
            output = io.BytesIO(); image.save(output, format='JPEG', quality=90)
            return output.getvalue(), image.size
    except Exception:
        raise VisionError('INVALID_IMAGE', 400) from None

def infer(data: bytes, session_id: str, stream_id: str, frame_id: int, model=None):
    start = time.monotonic()
    jpeg, (width, height) = prepare_image(data)
    key, configured_model = configuration()
    model = model or configured_model
    if not key:
        raise VisionError('UNCONFIGURED', 503)
    if not model or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-' for c in model):
        raise VisionError('INVALID_MODEL', 400)
    payload = {'contents':[{'parts':[
        {'inline_data': {'mime_type':'image/jpeg','data':base64.b64encode(jpeg).decode()}},
        {'text': (ASSETS/'vision_prompt.txt').read_text()}]}],
        'generationConfig': {'temperature':0.2,'responseMimeType':'application/json',
                             'responseSchema':json.loads((ASSETS/'vision_schema.json').read_text())}}
    request = urllib.request.Request(
        f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
        data=json.dumps(payload).encode(), headers={'x-goog-api-key':key,'Content-Type':'application/json'})
    provider_start = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            root = json.load(response)
    except urllib.error.HTTPError as error:
        statuses = {401:'CREDENTIAL_FAILURE',403:'CREDENTIAL_FAILURE',404:'MODEL_UNAVAILABLE',429:'QUOTA_OR_RATE_LIMIT'}
        raise VisionError(statuses.get(error.code, 'API_FAILURE'), error.code) from None
    except TimeoutError:
        raise VisionError('TIMEOUT', 504) from None
    except (OSError, urllib.error.URLError):
        raise VisionError('NETWORK_UNAVAILABLE', 503) from None
    try:
        candidate = root.get('candidates', [])[0]
        if candidate.get('finishReason') != 'STOP':
            raise VisionError('INCOMPLETE_RESPONSE')
        text = ''.join(p.get('text','') for p in candidate['content']['parts'] if not p.get('thought', False))
        detections = parse_response(text)
    except (IndexError, KeyError, TypeError):
        raise VisionError('BLOCKED_OR_EMPTY_RESPONSE') from None
    completed = time.monotonic()
    if os.environ.get('VESCURUS_RECORD_CV') == '1':
        records = ROOT / '.local/vision/provider-records'
        records.mkdir(parents=True, exist_ok=True)
        record = {'requestId':str(uuid.uuid4()), 'sessionId':session_id, 'streamId':stream_id,
                  'frameId':frame_id, 'model':model, 'width':width, 'height':height,
                  'imageSha256':hashlib.sha256(jpeg).hexdigest(),
                  'promptSha256':hashlib.sha256((ASSETS/'vision_prompt.txt').read_bytes()).hexdigest(),
                  'providerText':text, 'inferenceMs':round((completed-provider_start)*1000)}
        (records/(record['requestId']+'.json')).write_text(json.dumps(record,indent=2))
    return {'sessionId':session_id,'streamId':stream_id,'frameId':frame_id,
        'captureUptimeMs':int(start*1000),'width':width,'height':height,
        'engine':f'GEMINI/{model}','status':'SUCCESS','inferenceMs':round((completed-provider_start)*1000),
        'sourceAgeMs':round((completed-start)*1000),'jpegBase64':base64.b64encode(jpeg).decode(),
        'detections':detections,'rotationApplied':0,'mirrored':False,'coordinates':'normalized-yxyx-upright'}

class Handler(BaseHTTPRequestHandler):
    # One inference at a time. Requests do not create a queued stream of stale observations.
    inference_lock = threading.Lock()
    verified_model = None
    successful_requests = 0
    cache = OrderedDict()
    stream_frames = OrderedDict()
    next_allowed_at = 0.0
    def log_message(self, *args):
        pass  # Avoid logging submitted images, headers or credentials.
    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == '/health':
            key, model = configuration()
            self.reply(200, {'status':'configured' if key else 'unconfigured','engine':'GEMINI','model':model,
                             'inferenceVerified': self.verified_model == model, 'successfulRequests': self.successful_requests,'scope':'loopback validation API'})
        else: self.reply(404, {'status':'NOT_FOUND'})
    def do_POST(self):
        if self.path != '/v1/observations':
            return self.reply(404, {'status':'NOT_FOUND'})
        # Drain the bounded body before replying BUSY. Closing with unread bytes can
        # reset the connection on Windows and hide the HTTP error from the caller.
        try:
            self.connection.settimeout(10)
            size = int(self.headers.get('Content-Length','0'))
            if not 0 < size <= MAX_BODY: return self.reply(413, {'status':'INVALID_BODY_SIZE'})
            raw_body = self.rfile.read(size)
            if len(raw_body) != size: return self.reply(400, {'status':'INCOMPLETE_BODY'})
        except (ValueError, TimeoutError):return self.reply(400, {'status':'INVALID_BODY'})
        if not self.inference_lock.acquire(blocking=False):
            return self.reply(429, {'status':'BUSY'})
        context = {}
        try:
            body = json.loads(raw_body)
            frame_id = body['frameId']
            if type(frame_id) is not int or not 1 <= frame_id < 2**63: raise ValueError()
            for field in ('sessionId','streamId'):
                if not isinstance(body[field],str) or not 1 <= len(body[field]) <= 128: raise ValueError()
            context = {k: body[k] for k in ('sessionId','streamId','frameId')}
            request_id = body.get('requestId')
            if request_id is not None:
                if not isinstance(request_id,str) or not 1 <= len(request_id) <= 128: raise ValueError()
                context['requestId'] = request_id
            data = base64.b64decode(body['imageBase64'], validate=True)
            identity = (body['sessionId'],body['streamId'],frame_id)
            stream = identity[:2]
            digest = hashlib.sha256(data).hexdigest()
            if identity in self.cache:
                cached_digest, cached, completed = self.cache[identity]
                if digest != cached_digest: raise VisionError('FRAME_ID_CONFLICT',409)
                result = dict(cached)
                result.pop('requestId', None)
                if request_id is not None: result['requestId'] = request_id
                result['sourceAgeMs'] += round((time.monotonic()-completed)*1000)
                if result['sourceAgeMs'] >= 15000: raise VisionError('OBSERVATION_EXPIRED',410)
                return self.reply(200,result)
            if frame_id <= self.stream_frames.get(stream,0): raise VisionError('OBSOLETE_FRAME',409)
            if time.monotonic() < self.next_allowed_at: raise VisionError('PROVIDER_COOLDOWN',429)
            result = infer(data,body['sessionId'],body['streamId'],frame_id)
            if request_id is not None: result = {**result, 'requestId':request_id}
            type(self).cache[identity] = (digest,result,time.monotonic())
            while len(self.cache)>8:self.cache.popitem(last=False)
            type(self).stream_frames[stream] = frame_id
            self.stream_frames.move_to_end(stream)
            while len(self.stream_frames)>32:self.stream_frames.popitem(last=False)
            type(self).verified_model = result["engine"].split("/",1)[1]
            type(self).successful_requests += 1
            self.reply(200,result)
        except VisionError as e:
            if e.status == 'QUOTA_OR_RATE_LIMIT':type(self).next_allowed_at = time.monotonic()+60
            self.reply(e.http_status, {**context, 'status':e.status, 'engine':'GEMINI/'+configuration()[1]})
        except (ValueError, KeyError, TypeError): self.reply(400, {'status':'INVALID_REQUEST'})
        except Exception: self.reply(500, {'status':'INTERNAL_ERROR'})
        finally: self.inference_lock.release()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=8765)
    args = parser.parse_args()
    print(f'VESCURUS validation API at http://127.0.0.1:{args.port}; no automatic inference or paid setup',flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port), Handler).serve_forever()
