const detect = require('./detect.js');
const SR = 44100, N = 4096;

function tone(f, harmonics, amp, noise){
  const b = new Float32Array(N);
  for (let i = 0; i < N; i++){
    let v = 0;
    for (let h = 1; h <= harmonics; h++) v += Math.sin(2*Math.PI*f*h*i/SR) / h;
    b[i] = v * amp + (noise ? (Math.random()*2-1) * noise : 0);
  }
  return b;
}
function cents(a, b){ return 1200 * Math.log2(a/b); }

const cases = [
  ['low E  82.41 Hz  (guitar 6)',  82.41, 6, 0.3, 0.002],
  ['A2    110.00 Hz  (guitar 5)', 110.00, 6, 0.3, 0.002],
  ['D3    146.83 Hz',             146.83, 5, 0.3, 0.002],
  ['G3    196.00 Hz',             196.00, 5, 0.3, 0.002],
  ['C4    261.63 Hz  (uke C)',    261.63, 5, 0.3, 0.002],
  ['E4    329.63 Hz  (uke E)',    329.63, 4, 0.3, 0.002],
  ['G4    392.00 Hz  (uke g)',    392.00, 4, 0.3, 0.002],
  ['A4    440.00 Hz  (uke A)',    440.00, 4, 0.3, 0.002],
  ['A4 pure sine',                440.00, 1, 0.3, 0.000],
  ['A4 noisy, harmonic-rich',     440.00, 9, 0.25, 0.02],
];

let bad = 0;
console.log('note                              detected     err(cents)  clarity');
for (const [label, f, h, amp, nz] of cases){
  const r = detect(tone(f, h, amp, nz), SR);
  if (!r){ console.log(`${label.padEnd(33)} NOT DETECTED`); bad++; continue; }
  const e = cents(r.freq, f);
  const ok = Math.abs(e) < 5;
  if (!ok) bad++;
  console.log(`${label.padEnd(33)} ${r.freq.toFixed(2).padStart(8)} ${e.toFixed(2).padStart(12)}  ${r.clarity.toFixed(3)} ${ok?'':'  <-- OFF'}`);
}

// must stay silent on junk
const silence = new Float32Array(N);
const noise = new Float32Array(N); for (let i=0;i<N;i++) noise[i] = (Math.random()*2-1)*0.25;
const quiet = tone(440, 4, 0.004, 0.0005);
for (const [label, buf] of [['silence', silence], ['white noise', noise], ['very quiet tone', quiet]]){
  const r = detect(buf, SR);
  const ok = !r;
  if (!ok) bad++;
  console.log(`${('reject: '+label).padEnd(33)} ${r ? r.freq.toFixed(2)+' Hz  <-- SHOULD REJECT' : 'rejected  ok'}`);
}

// stability: 30 consecutive frames of the same note with jitter
let notes = new Set();
for (let k=0;k<30;k++){
  const r = detect(tone(196.0, 6, 0.3, 0.02), SR);
  if (r) notes.add(Math.round(69 + 12*Math.log2(r.freq/440)));
}
console.log(`\nstability over 30 noisy frames of G3: distinct notes reported = ${notes.size} (want 1)`);
if (notes.size !== 1) bad++;
console.log(bad === 0 ? '\nALL CHECKS PASSED' : `\n${bad} CHECK(S) FAILED`);
process.exit(bad === 0 ? 0 : 1);
