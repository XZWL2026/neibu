// One content source for the console and the original documentation entry.
// Paths and fields follow contracts/openapi/public/xzwl-new-system-1.0.0.json.
export type DocExample = { title:string; method:'GET'|'POST'; path:string; body?:Record<string,unknown>; upload?:boolean; idempotency?:boolean; response:Record<string,unknown> };
export type DocSection = { id:string; title:string; group:'快速开始'|'API 指南'; description:string; paragraphs:string[]; endpoints:string[][]; fields:string[][]; examples:DocExample[] };
export const apiBaseUrl='https://aiyomio.cn';
const model='MODEL_ID';
const options:DocExample={title:'读取可用模型',method:'GET',path:'/api/v1/media/generation-options',response:{models:[{type:'VIDEO',model:'MODEL_ID',displayName:'模型展示名称',resolutions:['720p'],aspectRatios:['16:9'],maxCount:1,minDurationSeconds:4,maxDurationSeconds:15,durationStepSeconds:1,maxReferenceImages:2,referenceFollowsFirstFrame:false,omniReference:null}]}};
const taskResponse={id:'TASK_ID',status:'RUNNING',results:[]};
const generation=(video:boolean):DocSection=>{
 const type=video?'VIDEO':'IMAGE';
 const params={type,model,resolution:video?'720p':'2K',aspectRatio:video?'16:9':'1:1',count:1,durationSeconds:video?5:0};
 return {id:video?'videos':'images',title:video?'视频生成':'图片生成',group:'API 指南',
 description:video?'type: VIDEO。先确认模型支持的时长、分辨率与参考模式，再提交任务并查询生成结果。':'type: IMAGE。从可用模型开始，选择参数后提交图片任务；生成结果通过任务查询获取。',
 paragraphs:[`先用同一个 API_KEY 调用 GET /api/v1/media/generation-options，在 models 中选择 type 为 ${type} 的对象。将该对象的 model 原样填入请求；displayName 仅用于展示，不能代替 model。MODEL_ID 是占位符，不可直接提交。`,
 `例如模型列表返回 model: ${video?'seedance-2.5':'seedream-5.0-lite'} 时，请求应原样使用该值。仅在列表确实返回该值时使用；不要根据 displayName 拼接型号，也不要直接填写其他服务商的模型 ID。`,
 'resolution 从同一模型的 resolutions 数组中选择；aspectRatio 从 aspectRatios 中选择。示例中的分辨率、比例与时长只是填写示意，提交前应替换为所选模型实际支持的组合。',
 video?'视频 count 固定为 1，durationSeconds 必须使用所选模型支持的时长。示例中的 5 秒需要按模型能力调整。':'图片 count 为 1–4，且不得超过模型上限；durationSeconds 为 0。',
 '提交接口返回 HTTP 202，表示请求已接收。保存响应 id，并查询任务状态；接收成功不等于生成完成。',
 video?'参考素材使用 references。人像审核通过后，使用同一 fileId、portraitId 与对应 kind；全能参考需设置 referenceMode: OMNI，且模型支持该模式。':'参考图片使用上传返回的 fileId；请勿将本地文件路径直接填入请求。'],
 endpoints:[['POST','/api/v1/media/tasks','携带 Idempotency-Key 提交生成任务，返回 202']],
 fields:[
 ['type','string · 必填',`${type}。与所选模型的 type 一致；IMAGE 表示图片，VIDEO 表示视频。`],
 ['model','string · 建议显式传入',`模型的调用标识，取自 models 中 type 为 ${type} 的对象的 model 字段，原样保留大小写和版本后缀。不是 displayName，也不是 API Key。省略时默认 ${video?'seedance-2.5':'seedream-5.0-lite'}，默认模型也必须在当前账户可用列表中。`],
 ['prompt','string · 创建任务必填','描述主体、场景、风格及动作等生成要求；非空，最多 16000 个字符。不要嵌入 Base64 或 data: 媒体内容。'],
 ['resolution','string · 建议显式传入',`输出清晰度，取自所选模型的 resolutions；例如 ${video?'720p':'2K'}，以返回值为准，大小写须一致。省略时默认 ${video?'720p':'2K'}，但所选模型不一定支持此默认值。`],
 ['aspectRatio','string · 建议显式传入',`画面宽高比，取自该模型的 aspectRatios，例如 16:9 为横屏、9:16 为竖屏、1:1 为正方形。省略时默认 ${video?'16:9':'1:1'}；先确认模型支持。`],
 ['count','integer · 可选',video?'每次生成视频数量，固定为 1；省略或传 0 时按 1 处理。':'每次生成图片数量，1–4 且不大于该模型的 maxCount。省略或传 0 时按 1 处理。'],
 ['durationSeconds','integer',video?'生成视频时长，单位为秒；在 minDurationSeconds 与 maxDurationSeconds 之间按 durationStepSeconds 选择。省略或传 0 时默认 5 秒，所选模型须支持；不能传小数。':'图片没有视频时长，固定填 0，省略也按 0 处理。'],
 ['references','array · 可选','参考素材对象数组；不使用素材时省略或传 []。已上传素材填写 fileId 与 kind。数量及媒体类型受模型和 referenceMode 限制，不能填写本地路径。'],
 ['references[].fileId','string','本人上传后得到的文件标识。推荐使用 fileId；与 httpsUrl 二选一，不可同时填写。OMNI 模式必须使用本人 fileId。'],
 ['references[].kind','string · 可选',video?'IMAGE / VIDEO / AUDIO，默认 IMAGE，必须与文件类型一致。VIDEO 和 AUDIO 素材仅用于 OMNI。':'图片参考填 IMAGE；省略默认 IMAGE。'],
 ['references[].portraitId','string · 可选','审核通过的人像标识，仅用于 VIDEO + OMNI。与审核时的 fileId、kind、model 对应，填写平台审核响应 id，不是上游素材地址。'],
 ['referenceMode','string · 可选',video?'默认 FIRST_LAST_FRAME：按数组顺序提供首帧、尾帧图片，张数不得超过 maxReferenceImages；0 表示不支持图片参考。OMNI：全能参考，须有非空 omniReference 能力且 references 不能为空。':'使用默认 FIRST_LAST_FRAME；图片生成不支持 OMNI。'],
 ['Idempotency-Key','请求头 · 创建任务必填','为一次创建操作生成唯一 REQUEST_ID；网络重试复用该值和同一个请求体。改模型、参数或新建任务时使用新值。']
 ],
 examples:[{title:video?'提交视频生成':'提交图片生成',method:'POST',path:'/api/v1/media/tasks',body:{...params,prompt:'雨后的森林，阳光透过树叶'},idempotency:true,response:taskResponse}]
 };
};
export const docSections:DocSection[]=[
 {id:'getting-started',title:'首次调用 API',group:'快速开始',description:'使用本人平台 API Key，在服务端调用图片与视频生成能力。',paragraphs:[`BASE_URL 为 ${apiBaseUrl}，不包含接口路径；API_KEY 使用平台发放的完整 Key。示例中的模型、文件与任务标识需要替换为真实值。`,'先读取可用模型，选择需要的生成类型；随后提交任务并查询结果。右侧模型响应为字段示意，MODEL_ID、能力值均需以本人实际响应替换；不是固定的可用模型列表。'],endpoints:[['GET','/api/v1/media/generation-options','读取可用模型与参数'],['POST','/api/v1/media/tasks','创建图片或视频任务'],['GET','/api/v1/media/tasks/{taskId}','查询任务状态和结果']],fields:[],examples:[options]},
 {id:'base-auth',title:'基础地址与鉴权',group:'快速开始',description:'使用 Authorization: Bearer 平台Key 认证；浏览器登录会话不能代替平台 Key。',paragraphs:['请求发送到部署后的 Gateway，接口路径以 /api/v1 开头。服务端示例无需浏览器 Cookie 或 CSRF。','完整 Key 仅放在 Authorization 请求头中。不要将 Key 写入 URL、网页代码或日志。BASE_URL 不包含 /api/v1，避免重复拼接。'],endpoints:[],fields:[['BASE_URL','基础地址',apiBaseUrl],['API_KEY','环境变量','本人平台 API Key'],['Idempotency-Key','请求头','创建生成任务和提交人像审核时提供；同次重试保持不变']],examples:[options]},
 {id:'models',title:'模型与能力',group:'快速开始',description:'model 决定使用哪一个模型生成内容。先获取当前账户可用模型，再填写请求参数。',
 paragraphs:[
 '1. 使用本人 API_KEY 请求 GET https://aiyomio.cn/api/v1/media/generation-options。响应 models 是当前账户可用的模型数组；右侧 JSON 仅演示字段结构和数值格式，不代表实时模型清单。',
 '2. 图片选择 type: IMAGE，视频选择 type: VIDEO。每个对象的 model 是接口调用值，displayName 是给用户看的名称。例如实际返回 model: seedance-2.5 和 displayName: Seedance 2.5 时，请求使用 seedance-2.5；MODEL_ID 只是示例占位符。',
 '3. 从同一个对象取 model、resolutions、aspectRatios 和数量/时长限制。不要把 A 模型的 model 与 B 模型的分辨率或时长混用，也不要自行把显示名称转换成接口标识。',
 '4. 将选定参数用于创建任务。切换模型后重新选择参数；模型可用性及分辨率会随账户配置改变，以最新响应为准。models 为空时，当前账户没有可用组合，先联系平台确认模型和定价配置。',
 '例如 minDurationSeconds 为 4、maxDurationSeconds 为 15、durationStepSeconds 为 1 时，可以选 4、5、6…15 秒。该例只解释字段含义，不代表所有视频模型均支持这些时长。',
 '首尾帧与全能参考是两套能力。maxReferenceImages 描述普通图片参考/视频首尾帧数量；OMNI 请查看 omniReference，不能用 maxReferenceImages 代替全能参考限制。'
 ],endpoints:[['GET','/api/v1/media/generation-options','携带 Authorization: Bearer 平台Key；返回 models 数组']],fields:[
 ['models[].model','string','调用时写入请求 model 的唯一标识。原样复制，区分大小写；不是展示名、API Key 或自行推测的供应商型号。'],
 ['models[].displayName','string','界面展示名称，便于辨认模型，不作为请求 model。'],
 ['models[].type','string','IMAGE 为图片生成，VIDEO 为视频生成；请求 type 必须与此一致。'],
 ['models[].resolutions','string[]','当前账户可用清晰度值。请求 resolution 只能从所选模型此数组选择。'],
 ['models[].aspectRatios','string[]','支持的宽高比；请求 aspectRatio 从此数组选择。'],
 ['models[].maxCount','integer','单次输出数量上限。图片还受 1–4 的限制，视频为 1。'],
 ['models[].minDurationSeconds / maxDurationSeconds','integer','视频时长的最小值/最大值，单位秒；图片均为 0。'],
 ['models[].durationStepSeconds','integer','视频时长步长，单位秒；图片为 0。视频在最小到最大值之间选择支持的整数秒。'],
 ['models[].maxReferenceImages','integer','视频普通首尾帧模式：0 为纯文本、1 支持首帧、2 支持首尾帧；图片为图片参考数量能力。'],
 ['models[].referenceFollowsFirstFrame','boolean','true 表示视频首尾帧模式使用首图比例；OMNI 使用选择的 aspectRatio。'],
 ['models[].omniReference','object / null','全能参考能力；null 表示不支持 OMNI。有此对象时仍需按下列字段选择素材。'],
 ['omniReference.maxImages / maxVideos / maxAudios / maxTotal','integer','图片、视频、音频各自数量上限，以及所有素材合计数量上限；同时满足。'],
 ['omniReference.maxVideoDurationSeconds / maxAudioDurationSeconds','integer','各类素材时长上限，单位秒；同类多条素材的累计时长也不能超过该上限。以服务器读取的文件时长为准。'],
 ['omniReference.maxVideoBytes / maxAudioBytes','integer','视频/音频单个文件的体积上限，单位字节。'],
 ['omniReference.audioOnly','boolean','true 允许只有音频作为参考；false 时至少需要一张图片或一段视频。']
 ],examples:[options]},
 {id:'workflow',title:'推荐调用流程',group:'快速开始',description:'按“读取模型 → 提交 → 查询”的顺序完成调用。',paragraphs:['1. GET /api/v1/media/generation-options：选择模型支持的参数。','2. POST /api/v1/media/tasks：添加 prompt，并使用唯一 Idempotency-Key 提交。','3. GET /api/v1/media/tasks/{taskId}：保存并使用提交响应中的 id 查询状态与 results。'],endpoints:[],fields:[],examples:[options]},
 generation(false),generation(true),
 {id:'reference-images',title:'参考图片上传',group:'API 指南',description:'上传本地 JPEG / PNG 图片，获取用于生成任务的 fileId。',
 paragraphs:['使用 Authorization: Bearer 平台Key，提交 multipart/form-data，请求字段名为 file，类型选择文件。支持 JPEG（.jpg / .jpeg）和 PNG（.png），文件必须非空且不超过 20 MiB（20 × 1024 × 1024 字节）。',
 '在 Apifox 的 Body 中选择 form-data，添加 file 字段并将类型设为 File，选择本地图片。不要使用 JSON、Base64 或把本地路径作为普通文本提交；Content-Type 及 boundary 由客户端自动生成。',
 '成功返回 HTTP 201。保存响应中的 fileId，在生成请求 references 中填写 {"fileId":"FILE_ID","kind":"IMAGE"}，并使用同一账号的 API Key。上传不会自动创建生成任务。',
 '示例中的 IMAGE_PATH 是本地图片路径；cURL / Node.js 示例使用环境变量 IMAGE_PATH，Python 示例同样从环境变量读取。'],
 endpoints:[['POST','/api/v1/files/reference-images','multipart/form-data 上传参考图片，返回 201']],
 fields:[['file','multipart 文件 · 必填','一张非空 JPEG / PNG 图片，最大 20 MiB；字段名固定为 file。'],['fileId','响应字段 · string','上传后的文件标识，用于生成请求 references[].fileId。'],['mimeType','响应字段 · string','实际文件类型：image/jpeg 或 image/png。'],['sizeBytes','响应字段 · integer','文件大小，单位为字节。'],['purpose','响应字段 · string','参考图片固定为 REFERENCE_IMAGE。'],['createdAt','响应字段 · string','文件创建时间，ISO 8601 格式。']],
 examples:[{title:'上传参考图片',method:'POST',path:'/api/v1/files/reference-images',upload:true,response:{fileId:'FILE_ID',mimeType:'image/png',sizeBytes:102400,purpose:'REFERENCE_IMAGE',createdAt:'2026-10-12T08:00:00Z'}}]},
 {id:'file-access',title:'获取生成文件 URL',group:'API 指南',description:'将生成结果的 fileId 换成可播放、预览或下载的临时 URL。',
 paragraphs:['先查询 GET /api/v1/media/tasks/{taskId}，从返回的 results 数组中取出 fileId。每个结果文件分别调用本接口；fileId 不是 taskId，也不是媒体 URL。',
 '使用创建任务时同一账号的 API Key，将真实 fileId 填入路径。成功返回 HTTP 200，响应 url 可用于图片预览、视频播放或下载。',
 'URL 有效期为 1 小时（3600 秒），以 expiresAt 返回的到期时间为准。过期后使用同一个 fileId 再次调用本接口即可获得新地址，无需重新生成。使用完整 url，包括其中的签名查询参数。'],
 endpoints:[['GET','/api/v1/files/{fileId}/access-url','获取本人结果文件的临时访问地址，返回 200']],
 fields:[['fileId','路径参数 · 必填','任务响应 results[].fileId，图片和视频使用相同接口。'],['url','响应字段 · string','带签名的临时文件访问地址，用于预览、播放或下载。'],['expiresAt','响应字段 · string','URL 到期时间，ISO 8601 格式；有效期为 1 小时。']],
 examples:[{title:'获取生成文件 URL',method:'GET',path:'/api/v1/files/{fileId}/access-url',response:{url:'https://storage.example.com/result?signature=EXAMPLE',expiresAt:'2026-10-12T09:00:00Z'}}]},
 {id:'materials',title:'人像库审核',group:'API 指南',description:'将本人上传的人像素材提交审核，通过后可用于视频全能参考。',paragraphs:['提交 fileId 与 model，使用 Idempotency-Key。kind 默认 IMAGE，也支持 VIDEO / AUDIO，必须与上传素材类型一致；音视频时长由存储信息确定。','保存提交返回的 id，使用该 id 查询审核状态。SUBMITTING / PROCESSING 表示尚在处理；ACTIVE 表示可用；FAILED 表示失败；UNCERTAIN 表示提交结果待确认，不应当作已通过。','审核状态为 ACTIVE 后，视频请求使用 referenceMode: OMNI，并在 references 中同时填写原 fileId、portraitId 与对应 kind。视频模型必须支持该模式。'],endpoints:[['POST','/api/v1/media/portraits','提交本人素材审核，携带 Idempotency-Key，返回 202'],['GET','/api/v1/media/portraits/{portraitId}','查询本人审核状态']],fields:[['fileId','string · 必填','本人上传返回的文件标识，1–128 个字符'],['model','string · 必填','填写后续视频生成使用的同一个 model，取自模型列表中支持人像引用的 VIDEO 模型；不是 displayName。1–128 个字符，须与之后引用审核结果的模型一致。'],['kind','string · 可选','IMAGE / VIDEO / AUDIO，默认 IMAGE'],['portraitId','路径参数','查询时使用提交返回的 id']],examples:[{title:'提交人像审核',method:'POST',path:'/api/v1/media/portraits',body:{fileId:'FILE_ID',model,kind:'IMAGE'},idempotency:true,response:{id:'PORTRAIT_ID',status:'PROCESSING'}},{title:'查询人像审核',method:'GET',path:'/api/v1/media/portraits/{portraitId}',response:{id:'PORTRAIT_ID',fileId:'FILE_ID',kind:'IMAGE',status:'ACTIVE'}}]},
 {id:'tasks',title:'任务查询',group:'API 指南',description:'图片与视频使用同一个任务查询接口，taskId 为提交响应中的 id。',paragraphs:['查询返回 status 和 results。只有任务成功并返回结果后，才使用 results 中的 index、fileId 读取生成文件。','任务结果中的 fileId 是文件标识，不是可直接打开的媒体 URL。请通过平台文件访问接口获取当前可用地址。'],endpoints:[['GET','/api/v1/media/tasks/{taskId}','查询本人图片或视频任务状态及结果']],fields:[['taskId','路径参数 · 必填','创建任务返回的 id'],['status','响应字段','生成任务当前状态'],['results','响应数组','成功结果的 index 与 fileId']],examples:[{title:'查询生成任务',method:'GET',path:'/api/v1/media/tasks/{taskId}',response:{id:'TASK_ID',status:'SUCCEEDED',results:[{index:0,fileId:'RESULT_FILE_ID'}]}}]}
];
export const retryText='同一请求重试必须复用原 Idempotency-Key 和相同请求体；修改参数时使用新的请求 ID。202 仅表示受理，须继续查询任务结果。计费和退款以实际账务流水为准。';
export const errorText='401：检查平台 Key 与账户状态；403 / 404：核对本人资源权限；409：检查幂等请求是否冲突；422：重新核对模型支持的参数；502 / 503：先查询已提交任务，避免重复创建。保留响应头 X-Request-Id 便于排查。';
export function sectionForHash(hash:string){return docSections.find(section=>`#${section.id}`===hash)??docSections.find(section=>section.id==='images')!;}
export const languages=['cURL','Python','Node.js'] as const;
export type DocLanguage=typeof languages[number];
export function requestExample(example:DocExample,language:DocLanguage):string{
 const path=example.path.replace('{taskId}','TASK_ID').replace('{portraitId}','PORTRAIT_ID').replace('{fileId}','FILE_ID');
 if(example.upload)return uploadExample(path,language);
 const headers:Record<string,string>={Authorization:'Bearer '+ '${API_KEY}'};
 if(example.body)headers['Content-Type']='application/json';if(example.idempotency)headers['Idempotency-Key']='${REQUEST_ID}';
 if(language==='Python')return ['import json, os, urllib.request', '', `BASE_URL = "${apiBaseUrl}"`, `url = BASE_URL + "${path}"`, 'headers = {"Authorization": "Bearer " + os.environ["API_KEY"]}',...(example.body?['headers["Content-Type"] = "application/json"']:[]),...(example.idempotency?['headers["Idempotency-Key"] = os.environ["REQUEST_ID"]']:[]),...(example.body?[`payload = ${JSON.stringify(example.body,null,2)}`, 'data = json.dumps(payload).encode("utf-8")']:['data = None']),`request = urllib.request.Request(url, data=data, headers=headers, method="${example.method}")`,'with urllib.request.urlopen(request) as response:','    print(response.status, response.read().decode("utf-8"))'].join('\n');
 if(language==='Node.js')return ['// Node.js 18+',`const BASE_URL = "${apiBaseUrl}";`,`const response = await fetch(BASE_URL + "${path}", {`,`  method: "${example.method}",`,'  headers: {','    Authorization: `Bearer ${process.env.API_KEY}`,',...(example.body?['    "Content-Type": "application/json",']:[]),...(example.idempotency?['    "Idempotency-Key": process.env.REQUEST_ID,']:[]),'  },',...(example.body?[`  body: JSON.stringify(${JSON.stringify(example.body,null,2)}),`]:[]),'});','console.log(response.status, await response.json());'].join('\n');
 return [`curl -X ${example.method} "${apiBaseUrl}${path}"`,...Object.entries(headers).map(([key,value])=>`  -H "${key}: ${value}"`),...(example.body?[`  --data '${JSON.stringify(example.body,null,2)}'`]:[])].join(' \\\n');
}

function uploadExample(path:string,language:DocLanguage):string{
 if(language==='Python')return [
  'import mimetypes, os, pathlib, urllib.request, uuid', '',
  `BASE_URL = "${apiBaseUrl}"`,
  'image_path = pathlib.Path(os.environ["IMAGE_PATH"])',
  'mime_type = mimetypes.guess_type(image_path.name)[0]',
  'if mime_type not in ("image/jpeg", "image/png"):',
  '    raise ValueError("IMAGE_PATH must be a JPEG or PNG image")',
  'boundary = "----Upload" + uuid.uuid4().hex',
  'filename = "reference.png" if mime_type == "image/png" else "reference.jpg"',
  'prefix = (f"--{boundary}\\r\\n"',
  '          f\'Content-Disposition: form-data; name="file"; filename="{filename}"\\r\\n\'',
  '          f"Content-Type: {mime_type}\\r\\n\\r\\n").encode("utf-8")',
  'data = prefix + image_path.read_bytes() + f"\\r\\n--{boundary}--\\r\\n".encode("utf-8")',
  'headers = {"Authorization": "Bearer " + os.environ["API_KEY"],',
  '           "Content-Type": "multipart/form-data; boundary=" + boundary}',
  `request = urllib.request.Request(BASE_URL + "${path}", data=data, headers=headers, method="POST")`,
  'with urllib.request.urlopen(request) as response:',
  '    print(response.status, response.read().decode("utf-8"))'
 ].join('\n');
 if(language==='Node.js')return [
  '// Node.js 18+; save as upload.mjs',
  'import { readFile } from "node:fs/promises";',
  'import { basename, extname } from "node:path";',
  `const BASE_URL = "${apiBaseUrl}";`,
  'const imagePath = process.env.IMAGE_PATH;',
  'const extension = extname(imagePath).toLowerCase();',
  'if (![".jpg", ".jpeg", ".png"].includes(extension)) throw new Error("IMAGE_PATH must be a JPEG or PNG image");',
  'const mimeType = extension === ".png" ? "image/png" : "image/jpeg";',
  'const form = new FormData();',
  'form.append("file", new Blob([await readFile(imagePath)], { type: mimeType }), basename(imagePath));',
  `const response = await fetch(BASE_URL + "${path}", {`,
  '  method: "POST",',
  '  headers: { Authorization: `Bearer ${process.env.API_KEY}` },',
  '  body: form,',
  '});',
  'console.log(response.status, await response.json());'
 ].join('\n');
 return [`curl -X POST "${apiBaseUrl}${path}"`,
  '  -H "Authorization: Bearer ${API_KEY}"',
  '  -F "file=@${IMAGE_PATH}"'].join(' \\\n');
}
