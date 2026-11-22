"""Reproducible online-photo evaluation. Expected labels stay in this harness, never in provider requests."""
import argparse, base64, hashlib, html, io, json, statistics, time, urllib.request, urllib.error
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps
from vision_api import ROOT

OUT = ROOT / '.local/vision'
SOURCES = {
 'a':'https://images.unsplash.com/photo-1615484477201-9f4953340fab?w=900&q=85',
 'b':'https://images.unsplash.com/photo-1528826007177-f38517ce9a8a?w=900&q=85',
 'c':'https://images.unsplash.com/photo-1669754839259-04d3abecfee6?w=1000&q=85',
 'd':'https://raw.githubusercontent.com/ultralytics/assets/main/im/zidane.jpg',
 'e':'https://images.unsplash.com/photo-1546094096-0df4bcaaa337?w=900&q=85',
 'f':'https://images.unsplash.com/photo-1571771894821-ce9b6c11b08e?w=900&q=85',
 'g':'https://cdn.esupply.co.jp/images/goods/M/MA-122HW_1.jpg',
 'h':'https://images.unsplash.com/photo-1589928183476-89aa0fdd30d0?w=900&q=85'}

ALIASES = {'eggplant':{'eggplant','aubergine'}, 'tomato':{'tomato','tomatoes','cherry tomato'},
 'banana':{'banana','bananas'}, 'onion':{'onion','onions','red onion','red onions','white onion','white onions'},
 'pepper':{'bell pepper','bell peppers','green bell pepper','red bell pepper','yellow bell pepper','green pepper','red pepper'},
 'egg':{'egg','eggs'}}

def matches(label, category):
 return label.strip().lower() in ALIASES[category]

def prepare():
 (OUT/'sources').mkdir(parents=True,exist_ok=True); (OUT/'cases').mkdir(exist_ok=True)
 metadata=[]
 for identifier,url in SOURCES.items():
  p=OUT/'sources'/f'{identifier}.jpg'
  if not p.exists():
   request=urllib.request.Request(url,headers={'User-Agent':'VESCURUS-CV-validation/1.0'})
   p.write_bytes(urllib.request.urlopen(request,timeout=25).read())
  metadata.append({'id':identifier,'url':url,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 (OUT/'sources/manifest.json').write_text(json.dumps(metadata,indent=2))
 def image(n): return ImageOps.exif_transpose(Image.open(OUT/'sources'/f'{n}.jpg')).convert('RGB')
 cases=[]
 def case(im, required, description, split='development',regions=None):
  identifier=f'{len(cases)+1:03}'
  im.thumbnail((960,960)); path=OUT/'cases'/f'{identifier}.jpg';im.save(path,quality=95)
  cases.append({'id':identifier,'path':str(path),'required':required,'description':description,'split':split,'regions':regions or {}})
 case(image('a'),['eggplant'],'single ingredient; lexical eggplant regression')
 case(image('e'),['tomato'],'three tomatoes; no count-to-recipe inference')
 case(image('f'),['banana'],'banana bunch; separate quantity source required')
 case(image('c'),['tomato','onion','pepper'],'crowded mixed ingredients; no invented mass')
 case(image('d'),[],'non-food people/control')
 case(image('g'),[],'white oval computer mouse/control')
 case(Image.new('RGB',(640,480),'#e0ddd6'),[],'empty synthetic viewing region')
 # Pixel-preserving placement fixtures, not AI-generated food. Photos retain their backgrounds.
 def tile(src,xy,size=(310,250),canvas_size=(960,720)):
  im=image(src);im.thumbnail(size);canvas=Image.new('RGB',canvas_size,'#ece7dc');canvas.paste(im,xy)
  x,y=xy;return canvas,[y/canvas.height,x/canvas.width,(y+im.height)/canvas.height,(x+im.width)/canvas.width]
 im,r=tile('a',(15,20));case(im,['eggplant'],'ingredient at upper left',regions={'eggplant':r})
 im,r=tile('a',(625,445));case(im,['eggplant'],'same ingredient moved to lower right',regions={'eggplant':r})
 case(image('a').rotate(90,expand=True),['eggplant'],'ninety-degree rotation; changed orientation')
 im=image('a');ImageDraw.Draw(im).rectangle((im.width*.45,im.height*.52,im.width*.68,im.height*.72),fill='#555555')
 case(im,['eggplant'],'partial occlusion with neutral rectangle; synthetic stress fixture')
 canvas=Image.new('RGB',(960,720),'#ece7dc');regions={}
 for src,category,xy in [('a','eggplant',(10,10)),('e','tomato',(620,20)),('f','banana',(310,370))]:
  im=image(src);im.thumbnail((320,320));canvas.paste(im,xy);x,y=xy
  regions[category]=[y/720,x/960,(y+im.height)/720,(x+im.width)/960]
 case(canvas,['eggplant','tomato','banana'],'three photographic tiles at separate positions',regions=regions)
 case(image('b'),['eggplant'],'held-out source photo; different placement and angle','held-out')
 case(image('h'),['egg'],'held-out legitimate white shell eggs','held-out')
 case(image('a'),['eggplant'],'same image repeated; results are observations, not additions','regression')
 (OUT/'cases/manifest.json').write_text(json.dumps(cases,indent=2))
 return cases

def evaluate(result, case):
 detections=[d for d in result['detections'] if d['supported'] and d['confidence']>=.5]
 labels=[d['label'] for d in detections]
 if not case['required']: return not detections, 'expected no food; got '+', '.join(labels)
 missing=[category for category in case['required'] if not any(matches(l,category) for l in labels)]
 wrong=[l for l in labels if not any(matches(l,c) for c in case['required'])]
 misplaced=[]
 for category, region in case['regions'].items():
  candidates=[d for d in detections if matches(d['label'],category)]
  def inside(d):
   b=d['box_2d'];cx=(b['left']+b['right'])/2;cy=(b['top']+b['bottom'])/2
   return region[1]<=cx<=region[3] and region[0]<=cy<=region[2]
  if not any(inside(d) for d in candidates):misplaced.append(category)
 return not missing and not wrong and not misplaced, f'labels={labels}; missing={missing}; unexpected={wrong}; position failures={misplaced}'

def render(result, identifier):
 image=Image.open(io.BytesIO(base64.b64decode(result['jpegBase64']))).convert('RGB');draw=ImageDraw.Draw(image)
 for d in result['detections']:
  b=d['box_2d'];rect=(int(b['left']*image.width),int(b['top']*image.height),int(b['right']*image.width),int(b['bottom']*image.height))
  draw.rectangle(rect,outline='#00ae50',width=3);draw.text((rect[0],max(0,rect[1]-12)),d['label'],fill='black',stroke_width=1,stroke_fill='white')
 image.save(OUT/'cases'/f'{identifier}-result.jpg')

def run(api, max_calls):
 cases=prepare();results=[]
 for case in cases[:max_calls]:
  started=time.monotonic();payload={'sessionId':'online-validation','streamId':'photos-v1','frameId':int(case['id']),
   'imageBase64':base64.b64encode(Path(case['path']).read_bytes()).decode()}
  try:
   request=urllib.request.Request(api+'/v1/observations',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
   with urllib.request.urlopen(request,timeout=45) as response: result=json.load(response)
   elapsed=round((time.monotonic()-started)*1000)
   passed,detail=evaluate(result,case);render(result,case['id'])
   (OUT/'cases'/f'{case["id"]}-response.json').write_text(json.dumps(result,indent=2))
   row={**case,'outcome':'PASS' if passed else 'FAIL','observed':detail,'inferenceMs':result['inferenceMs'],'apiRoundTripMs':elapsed,'engine':result['engine']}
  except urllib.error.HTTPError as error:
   with error: body=json.loads(error.read())
   row={**case,'outcome':'BLOCKED','observed':body.get('status','HTTP_ERROR'),'httpStatus':error.code}
  except Exception as error: row={**case,'outcome':'BLOCKED','observed':type(error).__name__}
  results.append(row);print(case['id'],row['outcome'],row['observed'],row.get('inferenceMs',''),flush=True)
  (OUT/'results.json').write_text(json.dumps(results,indent=2))
  # No retries. Stop when quota is exhausted; do not hammer an unavailable service.
  if row.get('httpStatus') in (401,403,429):break
  if len([r for r in results[-3:] if r['outcome']=='BLOCKED'])==3:break
  remaining=6-(time.monotonic()-started)
  if remaining>0:time.sleep(remaining)
 latencies=[r['inferenceMs'] for r in results if 'inferenceMs' in r]
 summary={'cases':len(results),'pass':sum(r['outcome']=='PASS' for r in results),'fail':sum(r['outcome']=='FAIL' for r in results),'blocked':sum(r['outcome']=='BLOCKED' for r in results),'medianInferenceMs':statistics.median(latencies) if latencies else None,'minInferenceMs':min(latencies) if latencies else None,'maxInferenceMs':max(latencies) if latencies else None,'scope':'Online photographs plus documented synthetic placement transforms. Not physical camera or two-phone validation.'}
 (OUT/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary),flush=True)
 cards=[]
 for r in results:
  cards.append(f'<article><h2>{r["id"]} {r["outcome"]}</h2><p>{html.escape(r["description"])} ({r["split"]})</p><img src="cases/{r["id"]}-result.jpg" onerror="this.src=\'cases/{r["id"]}.jpg\'"><p>{html.escape(r["observed"])}</p><p>Provider {r.get("inferenceMs","unavailable")} ms; API round trip {r.get("apiRoundTripMs","unavailable")} ms</p></article>')
 (OUT/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>VESCURUS CV evidence</title><style>body{font:16px system-ui;background:#eef0f4;padding:24px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:18px}article{background:white;border-radius:12px;padding:16px}img{width:100%;height:330px;object-fit:contain}pre{white-space:pre-wrap}</style><h1>VESCURUS CV evidence</h1><pre>'+html.escape(json.dumps(summary,indent=2))+'</pre><p>Expected labels and source filenames were never sent to the model. Position checks verify box centers within photographic tile regions, not benchmark IoU accuracy. Confidence scores are not measured accuracy.</p><main>'+''.join(cards)+'</main>')
 return summary

def review_saved():
 # Correct the ground-truth synonym omission without making any model requests or changing the prompt.
 path=OUT/'results.json';results=json.loads(path.read_text())
 if not (OUT/'initial-results.json').exists(): (OUT/'initial-results.json').write_text(json.dumps(results,indent=2))
 for row in results:
  row['initialOutcome']=row['outcome']
  response=OUT/'cases'/f'{row["id"]}-response.json'
  if response.exists():
   result=json.loads(response.read_text());passed,detail=evaluate(result,row)
   row.update(outcome='PASS' if passed else 'FAIL',observed=detail)
  if row['id']=='004':row['reviewNote']='Initial evaluator omitted white onion from the onion family. Source photograph visibly contains white onions at upper left; model output was correct. Prompt and model were unchanged.'
 path.write_text(json.dumps(results,indent=2))
 summary=json.loads((OUT/'summary.json').read_text());summary.update({'pass':sum(r['outcome']=='PASS' for r in results),'fail':sum(r['outcome']=='FAIL' for r in results),'annotationReview':'One evaluator synonym omission corrected after visual source inspection; initial-results.json retained.'})
 (OUT/'summary.json').write_text(json.dumps(summary,indent=2))
 report=(OUT/'report.html').read_text().replace('004 FAIL','004 PASS (annotation reviewed)')
 report=report.replace('unexpected=[&#x27;White Onion&#x27;]','unexpected=[] (white onion visibly present; evaluator synonym corrected)')
 report=report.replace('&quot;pass&quot;: 14','&quot;pass&quot;: 15').replace('&quot;fail&quot;: 1','&quot;fail&quot;: 0')
 (OUT/'report.html').write_text(report)
 print(json.dumps(summary,indent=2))

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--api',default='http://127.0.0.1:8765');p.add_argument('--max-calls',type=int,default=15);a=p.parse_args();run(a.api,min(20,max(1,a.max_calls)))
