import http from 'k6/http';
import { check, sleep } from 'k6';
export const options = { vus: Number(__ENV.VUS || 20), duration: '2m', thresholds: { http_req_duration: ['p(95)<2000'], checks: ['rate>0.99'] } };
export default function () {
 const key=`load-${__ENV.RUN_ID}-${__VU}-${__ITER}`;
 const r=http.post(`${__ENV.BASE_URL}/api/orders`,JSON.stringify({userId:10001,skuId:Number(__ENV.SKU_ID||1001),quantity:1,amount:149.50,currency:'CNY'}),{headers:{'Content-Type':'application/json','Idempotency-Key':key}});
 check(r,{'accepted or explicit capacity rejection':r=>r.status===202||r.status===429});
 sleep(0.1);
}
