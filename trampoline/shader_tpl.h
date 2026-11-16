static const char kGlassTpl[] = R"LGS(
// 阶段2 SF 内玻璃 SkSL 模板（printf 占位：bx by bw bh br，几何可从配置文件改）
// 母本 = LiquidGlassShader.kt AGSL_SOURCE main 管线（C9 band 在位版本）
// 裁剪口径：metaball/CONIC/tilt/mask 矩形剥除；色散/内糊为 uniform 门控可选（默认 0=关）
//   掩码≡本体 → dInVis=-sdRaw、单次 shapeAlpha；采样恒恒等映射（无 srcCoord/越界早退）
// uniform = uBackdrop + 六质感浮点（声明序 = conf 尾段 rf ds hg ag vb ib，角度弧度在注入侧换算）

uniform shader uBackdrop;

// ---------- 质感参数（运行时 uniform，缺省值与 spec/150 §6.6.1 字段表一致） ----------
uniform float uRefractionAmount;  // 折射位移强度 px
uniform float uDispersion;        // 色散强度（0=关）
uniform float uHighlight;         // 边缘高光强度
uniform float uLightAngle;        // 光源方向（弧度）
uniform float uVibrancy;          // 饱和度增益（1=不变）
uniform float uBlurRadius;        // 内部模糊半径 px（0=清晰透镜）

// ---------- 材质常量（非五参数项保持烘死） ----------
const float DEPTH = 1.0;              // 椭圆穹顶分量开
const float HL_WIDTH = 12.0;          // 高光带宽
const float HL_FALLOFF = 2.0;         // 方向点积衰减指数
const float HL_FLOOR = 0.4;           // 高光底量
const float HL_INNER_ATT = 0.0;       // 内侧衰减
const float INNER_GLOW = 0.0;         // 内侧辉光
const float MASK_ALPHA = 0.0;         // 遮罩压暗（0=不压暗）
const float3 MASK_COLOR = float3(0.0, 0.0, 0.0);

// ---------- 几何（printf 占位：bx by bw bh br） ----------
const float BX = %.1f;
const float BY = %.1f;
const float BW = %.1f;
const float BH = %.1f;
const float BR = %.1f;

// ---------- SDF（母本逐行） ----------
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 a = abs(coord);
    float2 cornerCoord = a - (halfSize - float2(radius));
    float2 dirEdge = normalize(max(cornerCoord, 0.0) + float2(0.0001));
    float2 dirInner = normalize(a + float2(0.0001));
    float depth = sdRoundedRect(coord, halfSize, radius);
    float t = smoothstep(-max(radius, 1.0) * 0.5, 0.0, depth);
    return sign(coord) * normalize(mix(dirInner, dirEdge, t));
}

float2 gradRefract(float2 coord, float2 halfSize, float radius) {
    float2 a = abs(coord);
    float2 c = halfSize - float2(radius);
    float2 q = a - c;
    float dy = (a.x > c.x) ? length(float2(q.x, a.y - halfSize.y)) : (halfSize.y - a.y);
    float dx = (a.y > c.y) ? length(float2(a.x - halfSize.x, q.y)) : (halfSize.x - a.x);
    float dc = length(q);
    float k = 3.0;
    float ty = max(dy, 0.0) + k;
    float tx = max(dx, 0.0) + k;
    float ta = abs(dc - radius) + k;
    bool arcValid = (q.x >= 0.0 && q.y >= 0.0 && dc > 0.0001);
    float tmin = min(ty, tx);
    if (arcValid && ta < tmin) {
        tmin = ta;
    }
    float sy = tmin / ty;
    float wy = sy * sy * sy;
    float sx = tmin / tx;
    float wx = sx * sx * sx;
    float wa = 0.0;
    if (arcValid) {
        float fade = smoothstep(0.0, max(radius, 1.0) * 0.5, dc);
        float sav = tmin / ta;
        wa = fade * sav * sav * sav;
    }
    float2 v = float2(wx, wy);
    if (wa > 0.0) {
        v += wa * (q / dc);
    }
    float z = max(wy + wx + wa, 1e-4);
    return sign(coord) * (v / z);
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

// ---------- 内部可选轻模糊（母本 7x7 可分离高斯，仅 uBlurRadius>0.01 时被调） ----------
float gauss(float x, float sigma) {
    return exp(-0.5 * (x / sigma) * (x / sigma));
}

float4 gaussianBlur(float2 coord, float spread) {
    float tapStep = spread * 1.41421356 / 3.0;
    float4 sum = float4(0.0);
    float total = 0.0;
    for (int y = -3; y <= 3; y++) {
        float wy = gauss(float(y), 2.25);
        float4 row = float4(0.0);
        float rowTotal = 0.0;
        for (int x = -3; x <= 3; x++) {
            float wx = gauss(float(x), 2.25);
            row += float4(uBackdrop.eval(coord + float2(float(x), float(y)) * tapStep)) * wx;
            rowTotal += wx;
        }
        sum += (row / rowTotal) * wy;
        total += wy;
    }
    return sum / total;
}

// ---------- 光谱色散（母本 7 通道复刻，零偏移恒等还原原色，仅 uDispersion>0 时被调） ----------
float4 spectralDispersion(float2 refractedCoord, float2 dispersedCoord) {
    float4 color = float4(0.0);
    float4 red = float4(uBackdrop.eval(refractedCoord + dispersedCoord));
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    float4 orange = float4(uBackdrop.eval(refractedCoord + dispersedCoord * (2.0 / 3.0)));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    float4 yellow = float4(uBackdrop.eval(refractedCoord + dispersedCoord * (1.0 / 3.0)));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    float4 green = float4(uBackdrop.eval(refractedCoord));
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    float4 cyan = float4(uBackdrop.eval(refractedCoord - dispersedCoord * (1.0 / 3.0)));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    float4 blue = float4(uBackdrop.eval(refractedCoord - dispersedCoord * (2.0 / 3.0)));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    float4 purple = float4(uBackdrop.eval(refractedCoord - dispersedCoord));
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    return color;
}

// ---------- main（母本管线，裁剪后） ----------
half4 main(float2 coord) {
    float2 halfSize = float2(BW, BH) * 0.5;
    float2 bodyCenter = float2(BX, BY) + halfSize;
    float radius = BR;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float refH = max(0.5 * min(BW, BH), 1.0); // spec/95：折射区高 = 短边一半

    float2 centeredCoord = coord - bodyCenter;
    float sdRaw = sdRoundedRect(centeredCoord, halfSize, radius);
    float sd = min(sdRaw, 0.0);
    float2 sdfGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

    float4 glass = float4(0.0);

    if (-sdRaw >= refH) {
        // 2a 内部：默认清晰底图，uBlurRadius>0 时轻模糊
        if (uBlurRadius > 0.01) {
            glass = gaussianBlur(coord, uBlurRadius);
        } else {
            glass = float4(uBackdrop.eval(coord));
        }
    } else {
        // 2b 折射环带
        float d = circleMap(1.0 - -sd / refH) * uRefractionAmount;
        float2 nBlend = gradRefract(centeredCoord, halfSize, gradRadius);
        float2 dome = centeredCoord / max(halfSize * halfSize, float2(0.0001));
        float2 domeN = float2(0.0);
        if (length(centeredCoord) > 0.0001) {
            domeN = dome / length(dome);
        }
        float2 gradCombined = nBlend + DEPTH * domeN;
        float2 n = float2(0.0);
        if (length(gradCombined) > 0.0001) {
            n = gradCombined / length(gradCombined);
        }
        float2 refrVec = -d * n * length(nBlend);
        float dispCap = min(halfSize.x, halfSize.y);
        float dispMag = length(refrVec);
        if (dispMag > dispCap && dispMag > 0.0001) {
            refrVec *= dispCap / dispMag;
        }
        float2 refractedCoord = coord + refrVec;
        // 环带采样优先级：内糊 > 色散 > 单采样（母本同构）
        if (uBlurRadius > 0.01) {
            glass = gaussianBlur(refractedCoord, uBlurRadius);
        } else if (uDispersion > 0.0001) {
            float dispI = uDispersion * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
            glass = spectralDispersion(refractedCoord, refrVec * dispI);
        } else {
            glass = float4(uBackdrop.eval(refractedCoord));
        }
    }

    // 内侧光（INNER_GLOW=0 恒零，保留母本结构）
    float hw = max(HL_WIDTH, 1.0);
    float bandCenter = max(hw * 0.5, 2.0);
    float dIn = -sdRaw;
    float innerT = clamp((dIn - bandCenter) / max(hw * 0.5, 1.0), 0.0, 1.0);
    float innerAtt = 1.0 - HL_INNER_ATT * innerT;
    float shapeAlpha = smoothstep(0.0, -2.0, sdRaw);
    float glowT = 1.0 - clamp(max(dIn, 0.0) / max(refH, 1.0), 0.0, 1.0);
    glass.rgb += INNER_GLOW * pow(glowT, 2.0 + HL_INNER_ATT * 3.0) * innerAtt * shapeAlpha;

    // 遮罩压暗（MASK_ALPHA=0 恒等）
    glass.rgb = mix(glass.rgb, MASK_COLOR, MASK_ALPHA);

    // vibrancy
    float luma = dot(glass.rgb, float3(0.213, 0.715, 0.072));
    glass.rgb = mix(float3(luma), glass.rgb, uVibrancy);

    // 掩码≡本体：跳过母本806行（避免 shapeAlpha 双乘），只留本体裁剪
    glass *= shapeAlpha;

    // C9 定向边缘高光（母本820-832逐行；sdMask≡sdRaw → dInVis=-sdRaw）
    float dInVis = -max(sdRaw, sdRaw);
    float band = 1.0 - smoothstep(0.0, hw, abs(dInVis - bandCenter));
    float2 lightDir = float2(cos(uLightAngle), sin(uLightAngle));
    float floorK = clamp(HL_FLOOR, 0.0, 1.0);
    float dirDot = pow(abs(dot(sdfGrad, lightDir)), HL_FALLOFF);
    float hl = floorK + (1.0 - floorK) * dirDot;
    glass.rgb += float3(hl * band * innerAtt * uHighlight);

    return half4(clamp(glass, 0.0, 1.0));
}
)LGS";
