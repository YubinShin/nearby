#!/usr/bin/env bash
# EKS 실험 클러스터 철거.
#
# **순서가 중요하다.** 네임스페이스를 먼저 지워야 StatefulSet 이 만든 EBS 볼륨이 함께 사라진다.
# 클러스터를 먼저 지우면 그 볼륨들이 고아로 남아 조용히 과금된다 — 아무도 안 보는 요금이라
# 다음 달 청구서에서 발견하게 된다. 그래서 마지막에 고아 볼륨을 **직접 확인**한다.
#
# 사용:  ./deploy/eks/down.sh            # 클러스터 삭제 (ECR 리포는 남긴다)
#        ./deploy/eks/down.sh --all      # ECR 리포지토리까지 삭제
set -uo pipefail

cd "$(dirname "$0")/../.."

REGION="${REGION:-ap-northeast-2}"
CLUSTER="${CLUSTER:-nearby}"
NS=nearby
ALL=0
[ "${1:-}" = "--all" ] && ALL=1

echo "▶ 1/4  네임스페이스 삭제 (PVC → EBS 볼륨까지 같이 사라진다)"
if kubectl get ns "$NS" >/dev/null 2>&1; then
	kubectl delete ns "$NS" --timeout=300s || echo "   (타임아웃 — 클러스터 삭제가 나머지를 정리한다)"
else
	echo "   ($NS 없음)"
fi

echo "▶ 2/4  클러스터 삭제 (10~15분)"
if eksctl get cluster --region "$REGION" --name "$CLUSTER" >/dev/null 2>&1; then
	eksctl delete cluster --region "$REGION" --name "$CLUSTER" --wait
else
	echo "   ($CLUSTER 없음)"
fi

# ---- 3. 고아 EBS 볼륨 확인 --------------------------------------------------
# 클러스터가 사라지면 이 볼륨들을 정리해줄 주체도 없어진다. available 상태로 남아 있으면
# 그게 곧 과금이다. 자동으로 지우지 않고 **보여주고 묻는다** — 이름만 보고 지우면
# 무관한 볼륨을 날릴 수 있다.
echo "▶ 3/4  고아 EBS 볼륨 확인"
ORPHANS=$(aws ec2 describe-volumes --region "$REGION" \
	--filters "Name=status,Values=available" \
	--query "Volumes[?Tags[?Key=='kubernetes.io/cluster/${CLUSTER}']].[VolumeId,Size,CreateTime]" \
	--output text 2>/dev/null)

if [ -n "${ORPHANS:-}" ]; then
	echo "   남아 있는 볼륨:"; echo "$ORPHANS" | sed 's/^/     /'
	echo "   지우려면:"
	echo "$ORPHANS" | awk '{print "     aws ec2 delete-volume --region '"$REGION"' --volume-id " $1}'
else
	echo "   없음 (깨끗하다)"
fi

# ---- 4. ECR ----------------------------------------------------------------
if [ "$ALL" = "1" ]; then
	echo "▶ 4/4  ECR 리포지토리 삭제"
	for repo in nearby-search-api nearby-indexer-batch nearby-postgis psp-elasticsearch-komoran; do
		aws ecr delete-repository --region "$REGION" --repository-name "$repo" --force >/dev/null 2>&1 \
			&& echo "   삭제: $repo" || echo "   (없음: $repo)"
	done
else
	echo "▶ 4/4  ECR 리포지토리는 남긴다 (다시 실험할 때 push 를 아낀다)"
	echo "   저장 요금이 아까우면: ./deploy/eks/down.sh --all"
	aws ecr describe-repositories --region "$REGION" \
		--query "repositories[?starts_with(repositoryName,'nearby-')||starts_with(repositoryName,'psp-')].repositoryName" \
		--output text 2>/dev/null | tr '\t' '\n' | sed 's/^/     /'
fi

cat <<EOF

━━━ 철거 완료 ━━━
남을 수 있는 것: 위에 표시된 고아 볼륨, ECR 이미지(--all 아니면).
EKS 컨트롤 플레인은 클러스터와 함께 사라졌다 — 이게 시간당 고정비의 정체였다.
EOF
