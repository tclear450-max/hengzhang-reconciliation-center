export interface Row { [key: string]: any }
let csrfToken = ''
export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type','application/json')
  if (csrfToken) headers.set('X-XSRF-TOKEN',csrfToken)
  const response = await fetch(`/api${path}`,{...options,headers,credentials:'same-origin'})
  if (!response.ok) {
    const problem = await response.json().catch(()=>({message:`请求失败 (${response.status})`}))
    if (response.status===401) window.dispatchEvent(new Event('session-expired'))
    throw new Error(problem.message || '操作未完成')
  }
  const result = await response.json()
  if (result.csrfToken) csrfToken=result.csrfToken
  return result
}
export async function download(path:string,filename:string) {
  const response=await fetch(`/api${path}`,{credentials:'same-origin'})
  if(!response.ok) throw new Error('下载失败，请检查登录状态')
  const url=URL.createObjectURL(await response.blob());const a=document.createElement('a')
  a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
export const money=(value:number|string|undefined) => {
  const cents=BigInt(String(value??0));const negative=cents<0n;const abs=negative?-cents:cents
  return `${negative?'-':''}${(abs/100n).toLocaleString('zh-CN')}.${(abs%100n).toString().padStart(2,'0')}`
}
export const labels:Record<string,string>={PLATFORM:'平台支付账',CLEARING:'渠道清算账',SETTLEMENT:'渠道结算账',TRANSACTION:'交易对账',FUND:'资金对账',BALANCE:'余额调节',ONE_TO_ONE:'逐笔核对 1:1',GROUPED:'按订单汇总 1:N',PAYMENT:'收款',REFUND:'退款',FEE:'手续费',TRANSFER:'转账',SUCCESS:'成功',PENDING:'处理中',FAILED:'失败',MATCHED:'对平',LEFT_ONLY:'A 方单边',RIGHT_ONLY:'B 方单边',AMOUNT_MISMATCH:'金额不符',STATUS_MISMATCH:'状态不符',DUPLICATE:'重复订单',OPEN:'待处理',INVESTIGATING:'排查中',PENDING_REVIEW:'待复核',RESOLVED:'已核销',SUPERSEDED:'已重对',MANUAL_CONFIRM:'人工资金确认',CONFIRM_LOSS:'确认损失',CONFIRM_INCOME:'确认收入',CORRECTED_EXTERNALLY:'业务系统已修复',APPROVED:'复核通过',REJECTED:'已驳回',SUSPENSE:'差异挂账',REVERSAL:'重对冲销',RESOLUTION:'核销凭证',RECON_OFFSET:'对账过渡账户',MANUAL_CLEARING:'人工核销账户',CONFIRMED_LOSS:'已确认损失',CONFIRMED_INCOME:'已确认收入',LOGIN:'登录',LOGOUT:'退出',CREATE_CHANNEL:'创建账户',SAVE_PROJECT:'保存项目',IMPORT:'导入账单',RUN:'执行对账',RERUN:'重新对账',INVESTIGATE:'开始排查',SUBMIT_RESOLUTION:'提交核销',APPROVE_RESOLUTION:'核销复核通过',REJECT_RESOLUTION:'核销驳回',EXPORT:'导出差异',SCHEDULE_FAILED:'调度失败'}
export const label=(value:string) => labels[value] ?? (value?.startsWith('SUSPENSE:P')?`项目 ${value.split(':P')[1]} 挂账账户`:value)
export const time=(value:string) => value?.replace('T',' ').slice(0,19) ?? '—'
export function plusDay(date:string,n=1) {const d=new Date(`${date}T12:00:00+08:00`);d.setDate(d.getDate()+n);return new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Hong_Kong',year:'numeric',month:'2-digit',day:'2-digit'}).format(d)}
