package tunnel

import (
	"math/rand"
	"sync"
	"time"
)

type DualPathSelector struct {
	mu               sync.Mutex
	rnd              *rand.Rand
	ewma             float64
	consecutiveFails int
	degraded         bool
	probeTick        uint64
}

func NewDualPathSelector() *DualPathSelector {
	return newDualPathSelector(time.Now().UnixNano())
}

func newDualPathSelector(seed int64) *DualPathSelector {
	return &DualPathSelector{
		rnd:  rand.New(rand.NewSource(seed)),
		ewma: 1,
	}
}

func (s *DualPathSelector) PreferQUIC() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.degraded {
		s.probeTick++
		return s.probeTick%10 == 0
	}
	p := 0.38 + 0.54*s.ewma
	if p < 0.42 {
		p = 0.42
	}
	if p > 0.90 {
		p = 0.90
	}
	return s.rnd.Float64() < p
}

func (s *DualPathSelector) RecordQuicOutcome(ok bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ok {
		s.consecutiveFails = 0
		s.degraded = false
		s.ewma = 0.82*s.ewma + 0.18
		if s.ewma > 1 {
			s.ewma = 1
		}
		return
	}
	s.consecutiveFails++
	s.ewma *= 0.82
	if s.consecutiveFails >= 2 {
		s.degraded = true
	}
}
